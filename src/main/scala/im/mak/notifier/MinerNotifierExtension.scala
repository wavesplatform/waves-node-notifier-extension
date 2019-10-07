package im.mak.notifier

import com.wavesplatform.account.{AddressOrAlias, KeyPair, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.extensions.{Extension, Context => ExtensionContext}
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.wavesplatform.utils.ScorexLogging
import im.mak.notifier.settings.MinerNotifierSettings
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import net.ceedubs.ficus.Ficus._
import scalaj.http.Http

import scala.concurrent.Future

class MinerNotifierExtension(context: ExtensionContext) extends Extension with ScorexLogging {
  private[this] val minerKeyPair: KeyPair     = context.wallet.privateKeyAccounts.head
  private[this] val minerPublicKey: PublicKey = minerKeyPair.publicKey
  private[this] val settings                  = context.settings.config.as[MinerNotifierSettings]("mining-notifier")

  @volatile
  private[this] var lastKnownHeight = 0

  def blockUrl(height: Int): String = settings.blockUrl.format(height)

  def checkNextBlock(): Unit = {
    val height = context.blockchain.height
    if (height == lastKnownHeight + 1) { // otherwise, most likely, the node is not yet synchronized
      val block = context.blockchain.blockAt(lastKnownHeight).get

      if (settings.notifications.leasing) {
        val leased = block.transactionData.collect {
          case tx: LeaseTransaction =>
            if (tx.recipient.isMiner) tx.amount
            else 0
        }.sum
        val leaseCanceled = block.transactionData.collect {
          case tx: LeaseCancelTransaction =>
            context.blockchain.leaseDetails(tx.leaseId) match {
              case Some(lease) if lease.recipient.isMiner => lease.amount
              case None                                   => 0
            }
        }.sum

        if (leased != leaseCanceled)
          Notifications.info(
            s"Leasing amount was ${if (leased > leaseCanceled) "increased" else "decreased"}" +
              s" by ${Format.waves(Math.abs(leased - leaseCanceled))} Waves at ${blockUrl(lastKnownHeight)}"
          )
      }

      if (settings.notifications.wavesReceived) {
        val wavesReceived = block.transactionData.collect {
          case mt: MassTransferTransaction if mt.assetId == Waves =>
            mt.transfers.collect {
              case t if t.address.isMiner => t.amount
            }.sum
          case t: TransferTransaction if t.assetId == Waves && t.recipient.isMiner => t.amount
        }.sum

        if (wavesReceived > 0) Notifications.info(s"Received ${Format.waves(wavesReceived)} Waves at ${blockUrl(lastKnownHeight)}")
      }

      val mrtReceived = block.transactionData.collect {
        case mt: MassTransferTransaction => mt.transfers.map(t => if (t.address.isMiner && mt.assetId.isMrt) t.amount else 0).sum
        case t: TransferTransaction =>
          if (t.recipient.isMiner && t.assetId.isMrt) t.amount else 0
      }.sum

      if (settings.notifications.mrtReceived && mrtReceived > 0)
        Notifications.info(s"Received ${Format.mrt(mrtReceived)} MRT at ${blockUrl(lastKnownHeight)}")

      val miningReward = if (block.getHeader().signerData.generator.toAddress.isMiner) {
        val blockFee     = context.blockchain.totalFee(lastKnownHeight).get
        val prevBlockFee = context.blockchain.totalFee(lastKnownHeight - 1).get
        val reward       = (prevBlockFee * 0.6 + blockFee * 0.4).toLong
        Notifications.info(s"Mined ${Format.waves(reward)} Waves ${blockUrl(lastKnownHeight)}")
        reward
      } else 0L

      Payouts.registerBlock(height, miningReward, mrtReceived)

      if (settings.payout.enabled && height % settings.payout.interval == 0) {
        Payouts.initPayouts(settings.payout, settings.mrtId, context.blockchain, minerPublicKey.toAddress)
        Payouts.finishUnconfirmedPayouts(settings.payout, context.utx, context.blockchain, minerKeyPair)
      }
    }
    lastKnownHeight = height
  }

  override def start(): Unit = {
    import scala.concurrent.duration._
    Notifications.info(s"$settings")

    lastKnownHeight = context.blockchain.height
    //TODO wait until node is synchronized
    val generatingBalance = context.blockchain.generatingBalance(minerPublicKey.toAddress)

    if (settings.notifications.startStop) {
      Notifications.info(
        s"Started at $lastKnownHeight height for miner ${minerPublicKey.toAddress.stringRepr}. " +
          s"Generating balance: ${Format.waves(generatingBalance)} Waves"
      )
    }

    if (context.settings.minerSettings.enable) {
      if (generatingBalance < 1000 * 100000000)
        Notifications.warn(
          s"Node doesn't mine blocks!" +
            s" Generating balance is ${Format.waves(generatingBalance)} Waves but must be at least 1000 Waves"
        )
      if (context.blockchain.hasScript(minerPublicKey.toAddress))
        Notifications.warn(
          s"Node doesn't mine blocks! Account ${minerPublicKey.toAddress.stringRepr} is scripted." +
            s" Send SetScript transaction with null script or use another account for mining"
        )
      if (settings.notifications.mrtReceived) {
        val id = ByteStr.decodeBase58(settings.mrtId).getOrElse(ByteStr.empty)
        if (id.isEmpty)
          Notifications.warn(s"""Can't parse "${settings.mrtId}" MRT Id! These notifications will not be sent""")
        else if (context.blockchain.transactionInfo(id).isEmpty)
          Notifications.warn(s"""Can't find transaction "${settings.mrtId}"! MRT notifications will not be sent""")
      }

      Observable
        .interval(1 seconds) // blocks are mined no more than once every 5 seconds
        .foreachL(_ => checkNextBlock())
        .runAsyncLogErr
    } else {
      Notifications.error("Mining is disabled! Enable this (waves.miner.enable) in the Node config and restart node")
      shutdown()
    }
  }

  override def shutdown(): Future[Unit] = Future {
    Notifications.info(s"Turned off at $lastKnownHeight height for miner ${minerPublicKey.toAddress}")
  }

  private[this] object Notifications {
    def sendNotification(text: String): Unit = {
      Http(settings.webhook.url)
        .headers(
          settings.webhook.headers.flatMap(
            s =>
              s.split(":") match {
                case Array(a, b) =>
                  Seq((a.trim, b.trim))
                case _ =>
                  log.error(s"""Can't parse "$s" header! Please check "webhook.headers" config. Its values must be in the "name: value" format""")
                  Seq()
              }
          )
        )
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

    def error(message: String): Unit = {
      log.error(message)
      sendNotification(message)
    }
  }

  private[this] implicit class AddressExt(a: AddressOrAlias) {
    def isMiner: Boolean =
      context.blockchain.resolveAlias(a).exists(_ == minerPublicKey.toAddress)
  }

  private[this] implicit class AssetExt(a: Asset) {
    def isMrt: Boolean = a match {
      case Asset.IssuedAsset(id) => Base58.encode(id) == settings.mrtId
      case Asset.Waves           => false
    }
  }
}
