package im.mak.notifier

import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.utils.{Base58, _}
import com.wavesplatform.state.Blockchain
import com.wavesplatform.state.diffs.FeeValidation
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.utils.ScorexLogging
import com.wavesplatform.utx.UtxPool
import im.mak.notifier.PayoutDB.model.Payout
import im.mak.notifier.settings.PayoutSettings
import im.mak.notifier.settings.PayoutSettings.Token

object Payouts extends ScorexLogging {
  def initPayouts(settings: PayoutSettings, mrtAssetId: String, blockchain: Blockchain, address: Address): Unit = {
    if (!settings.enabled || blockchain.height < settings.fromHeight) return

    val last = PayoutDB.lastPayoutHeight()
    if ((blockchain.height - last) < settings.interval) return

    val fromHeight = last + 1
    val toHeight   = blockchain.height - 1

    val leases = blockchain.collectActiveLeases(fromHeight, toHeight) { lease =>
      lazy val height = blockchain.transactionHeight(lease.id())
      lease.sender.toAddress == address && height.exists(h => (h - 1000) >= toHeight)
    }

    val generatingBalance        = blockchain.balanceSnapshots(address, fromHeight, blockchain.lastBlockId.get).map(_.effectiveBalance).max
    val (wavesReward, mrtReward) = PayoutDB.calculateReward(fromHeight, toHeight)
    log.info(s"Registering payout $fromHeight - $toHeight: ${Format.waves(wavesReward)} Waves")
    PayoutDB.addPayout(fromHeight, toHeight, wavesReward, None, generatingBalance, leases)
    PayoutDB.addPayout(fromHeight, toHeight, mrtReward, Some(mrtAssetId), generatingBalance, leases)

    settings.additionalTokens.foreach {
      case Token(assetId, amount) =>
        PayoutDB.addPayout(fromHeight, toHeight, amount, Some(assetId), generatingBalance, leases)
    }
  }

  def finishUnconfirmedPayouts(settings: PayoutSettings, utx: UtxPool, blockchain: Blockchain, key: KeyPair): Unit = {
    def commitPayout(payout: Payout): Unit = {
      val total = payout.generatingBalance
      val transfers = payout.activeLeases.groupBy(_.sender).mapValues { leases =>
        val amount = leases.map(_.amount).sum
        val share  = amount.toDouble / total
        payout.amount * share
      }

      val assetId     = Asset.fromString(payout.assetId)
      val txTransfers = transfers.map { case (sender, amount) => MassTransferTransaction.ParsedTransfer(sender.toAddress, amount.toLong) }.toList.ensuring(_.map(_.amount).sum <= payout.amount, "Incorrect payments total amount")
      val fee = {
        val dummyTx = MassTransferTransaction(assetId, key, txTransfers, System.currentTimeMillis(), 0, Array.emptyByteArray, Nil)
        FeeValidation.getMinFee(blockchain, blockchain.height, dummyTx).fold(_ => FeeValidation.FeeUnit * 2, _.minFeeInWaves)
      }

      val transaction =
        MassTransferTransaction.selfSigned(assetId, key, txTransfers, System.currentTimeMillis(), fee, Array.emptyByteArray).explicitGet()
      utx.putIfNew(transaction).resultE match {
        case Right(_) =>
          log.info(s"Committing payout #${payout.id}: $transaction")
          PayoutDB.setPayoutTxId(payout.id, Base58.encode(transaction.id()), blockchain.height)
        case Left(error) =>
          log.error(s"Error committing payout #${payout.id}: $transaction", new IllegalArgumentException(error.toString))
      }
    }

    val unconfirmed = PayoutDB.unconfirmedPayouts()
    unconfirmed foreach { p =>
      p.txId match {
        case Some(txId) =>
          blockchain.transactionHeight(Base58.decode(txId)) match {
            case Some(height) if height >= (p.txHeight + 100) => PayoutDB.confirmPayout(p.id)
            case None                                         => commitPayout(p)
            case _                                            => // Wait for more confirmations
          }

        case None =>
          commitPayout(p)
      }
    }
  }

  def registerBlock(height: Int, wavesReward: Long, mrtReward: Long): Unit =
    PayoutDB.addMinedBlock(height, wavesReward, mrtReward)
}
