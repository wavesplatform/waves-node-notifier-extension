package im.mak.notifier

import java.text.DecimalFormat

import com.wavesplatform.account.{AddressOrAlias, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.extensions.{Extension, Context => ExtensionContext}
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.wavesplatform.utils.ScorexLogging
import im.mak.notifier.settings.MinerNotifierSettings
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import net.ceedubs.ficus.Ficus._
import scalaj.http.Http

import scala.concurrent.Future

class MinerNotifierExtension(context: ExtensionContext) extends Extension with ScorexLogging {

  val minerPublicKey: PublicKey = context.wallet.privateKeyAccounts.head.publicKey
  private[this] val settings = context.settings.config.as[MinerNotifierSettings]("mining-notifier")
  var lastKnownHeight = 0

  def mrt(tokens: Long): String = new DecimalFormat("###,###.##")
    .format((BigDecimal(tokens) / 100).doubleValue())

  def waves(wavelets: Long): String = new DecimalFormat("###,###.########")
    .format((BigDecimal(wavelets) / 100000000).doubleValue())

  def blockUrl(height: Int): String = settings.blockUrl.replaceAll("%s", height.toString)

  def sendNotification(text: String): Unit = {
    Http(settings.webhook.url)
      .headers(settings.webhook.headers.flatMap(s => s.split(":") match {
        case Array(a, b) =>
          Seq((a.trim, b.trim))
        case _ =>
          log.error(s"""Can't parse "$s" header! Please check "webhook.headers" config. Its values must be in the "name: value" format""")
          Seq()
      }))
      .postData(settings.webhook.body.replaceAll("%s", text))
      .method(settings.webhook.method)
      .asString
  }

  def info(message: String): Unit = {
    log.info(message)
    sendNotification(message)
  }

  def warn(message: String): Unit = {
    log.warn(message)
    sendNotification(message)
  }

  def err(message: String): Unit = {
    log.error(message)
    sendNotification(message)
  }

  implicit class IsMiner(a: AddressOrAlias) {
    def isMiner: Boolean =
      context.blockchain.resolveAlias(a).right.get.stringRepr == minerPublicKey.toAddress.stringRepr
  }

  implicit class AssetId(a: Asset) {
    def isMrt: Boolean = a.maybeBase58Repr.get == settings.mrtId
  }

  def checkNextBlock(): Unit = {
    val height = context.blockchain.height
    if (height == lastKnownHeight + 1) { // otherwise, most likely, the node is not yet synchronized
      val block = context.blockchain.blockAt(lastKnownHeight).get

      if (settings.notifications.leasing) {
        val leased = block.transactionData.map {
          case l: LeaseTransaction =>
            if (l.recipient isMiner)
              l.amount
            else 0
        }.sum
        val leaseCanceled = block.transactionData.map {
          case l: LeaseCancelTransaction =>
            val lease = context.blockchain.leaseDetails(l.leaseId).get
            if (lease.recipient isMiner)
              lease.amount
            else 0
        }.sum

        if (leased != leaseCanceled)
          info(s"Leasing amount was ${if (leased > leaseCanceled) "increased" else "decreased"}" +
            s" by ${waves(Math.abs(leased - leaseCanceled))} Waves at ${blockUrl(lastKnownHeight)}")
      }

      if (settings.notifications.wavesReceived) {
        val wavesReceived = block.transactionData.map {
          case mt: MassTransferTransaction if mt.assetId == Waves => mt.transfers.collect {
            case t if t.address.isMiner => t.amount
          }.sum
          case t: TransferTransaction if t.assetId == Waves && t.recipient.isMiner => t.amount
          case _ => 0
        }.sum
        info(s"Received ${waves(wavesReceived)} Waves at ${blockUrl(lastKnownHeight)}")
      }

      if (settings.notifications.mrtReceived) {
        val mrtReceived = block.transactionData.map {
          case mt: MassTransferTransaction => mt.transfers.map(t =>
            if (t.address.isMiner && mt.assetId.isMrt) t.amount else 0
          ).sum
          case t: TransferTransaction =>
            if (t.recipient.isMiner && t.assetId.isMrt) t.amount else 0
        }.sum
        info(s"Received ${mrt(mrtReceived)} MRT at ${blockUrl(lastKnownHeight)}")
      }

      if (minerPublicKey == block.getHeader().signerData.generator) {
        val blockFee = context.blockchain.totalFee(lastKnownHeight).get
        val prevBlockFee = context.blockchain.totalFee(lastKnownHeight - 1).get
        val reward = (prevBlockFee * 0.6 + blockFee * 0.4).toLong
        info(s"Mined ${waves(reward)} Waves ${blockUrl(lastKnownHeight)}")
      }
    }
    lastKnownHeight = height
  }

  override def start(): Unit = {
    import scala.concurrent.duration._
    info(s"$settings")

    lastKnownHeight = context.blockchain.height
    //TODO wait until node is synchronized
    val generatingBalance = context.blockchain.generatingBalance(minerPublicKey.toAddress)

    if (settings.notifications.startStop) {
      info(s"Started at $lastKnownHeight height for miner ${minerPublicKey.toAddress.stringRepr}. " +
        s"Generating balance: ${waves(generatingBalance)} Waves")
    }

    if (context.settings.minerSettings.enable) {
      if (generatingBalance < 1000 * 100000000)
        warn(s"Node doesn't mine blocks!" +
          s" Generating balance is ${waves(generatingBalance)} Waves but must be at least 1000 Waves")
      if (context.blockchain.hasScript(minerPublicKey.toAddress))
        warn(s"Node doesn't mine blocks! Account ${minerPublicKey.toAddress.stringRepr} is scripted." +
          s" Send SetScript transaction with null script or use another account for mining")
      if (settings.notifications.mrtReceived
        && context.blockchain.transactionInfo(ByteStr.decodeBase58(settings.mrtId).getOrElse(ByteStr.empty)).isDefined)
        warn(s"""Can't parse "${settings.mrtId}" MRT Id! These notifications will not be sent""")

      Observable.interval(1 seconds) // blocks are mined no more than once every 5 seconds
        .doOnNext(_ => Task.now(checkNextBlock()))
        .subscribe
    } else {
      err("Mining is disabled! Enable this (waves.miner.enable) in the Node config and restart node")
      shutdown()
    }
  }

  override def shutdown(): Future[Unit] = Future {
    info(s"Turned off at $lastKnownHeight height for miner ${minerPublicKey.toAddress.stringRepr}")
  }

}
