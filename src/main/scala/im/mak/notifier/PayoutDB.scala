package im.mak.notifier

import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.{Asset, Transaction, TransactionParsers}
import com.wavesplatform.utils.ScorexLogging

object PayoutDB extends ScorexLogging {
  import io.getquill.{MappedEncoding, _}

  private[this] lazy val ctx = new H2JdbcContext(SnakeCase, "mining-notifier.db.ctx")

  import ctx.{lift => liftQ, liftQuery => liftList, _}

  //noinspection TypeAnnotation
  object model {
    implicit val encodeTransaction = MappedEncoding[Transaction, Array[Byte]](_.bytes())
    implicit val decodeTransaction = MappedEncoding[Array[Byte], Transaction](TransactionParsers.parseBytes(_).get)

    case class MinedBlock(height: Int, reward: Long, mrtReward: Long)

    case class Payout(
        id: Int,
        fromHeight: Int,
        toHeight: Int,
        amount: Long,
        assetId: Option[String],
        generatingBalance: Long,
        activeLeases: Seq[LeaseTransaction],
        txId: Option[String],
        txHeight: Int,
        confirmed: Boolean
    )

    implicit val minedBlocksMeta = schemaMeta[MinedBlock]("mined_blocks")
    implicit val payoutsMeta     = schemaMeta[Payout]("payouts")
  }

  import model._

  def addMinedBlock(height: Int, reward: Long, mrtReward: Long): Unit = {
    val existing = {
      val q = query[MinedBlock].filter(_.height == liftQ(height))
      run(q).headOption
    }

    val q = if (existing.nonEmpty) quote {
      query[MinedBlock].insert(_.height -> liftQ(height), _.reward -> liftQ(reward), _.mrtReward -> liftQ(mrtReward))
    } else
      quote {
        query[MinedBlock].filter(_.height == height).update(v => v.reward -> (v.reward + reward), v => v.mrtReward -> (v.mrtReward + mrtReward))
      }
    log.info(
      s"Block at $height reward is ${Format.waves(existing.fold(0L)(_.reward) + reward)} Waves/${Format.mrt(existing.fold(0L)(_.mrtReward) + mrtReward)} MRT"
    )
    run(q)
  }

  def calculateReward(fromHeight: Int, toHeight: Int): (Long, Long) = {
    val q = quote {
      val blocks = query[MinedBlock].filter(v => v.height >= liftQ(fromHeight) && v.height <= liftQ(toHeight))
      (blocks.map(_.reward).sum, blocks.map(_.mrtReward).sum)
    }

    val (waves, mrt) = run(q)
    (waves.getOrElse(0L), mrt.getOrElse(0L))
  }

  def lastPayoutHeight(): Int = {
    val q = quote {
      query[Payout].map(_.toHeight).max
    }
    run(q).getOrElse(0)
  }

  def unconfirmedPayouts(): Seq[Payout] = {
    val q = quote {
      query[Payout].filter(_.confirmed == false)
    }
    run(q)
  }

  def addPayout(
      fromHeight: Int,
      toHeight: Int,
      amount: Long,
      assetId: Option[String],
      generatingBalance: Long,
      activeLeases: Seq[LeaseTransaction]
  ): Int = {
    val q = quote {
      query[Payout]
        .insert(
          _.fromHeight        -> liftQ(fromHeight),
          _.toHeight          -> liftQ(toHeight),
          _.amount            -> liftQ(amount),
          _.assetId           -> liftQ(assetId),
          _.generatingBalance -> liftQ(generatingBalance),
          _.activeLeases      -> liftList(activeLeases)
        )
        .returning(_.id)
    }
    val id = run(q)
    log.info(s"Payout registered: #$id ($fromHeight - $toHeight, $amount of ${Asset.fromString(assetId)}, ${activeLeases.length} leases)")
    id
  }

  def setPayoutTxId(id: Int, txId: String, txHeight: Int): Unit = {
    val q = quote {
      query[Payout].filter(_.id == liftQ(id)).update(_.txId -> Some(liftQ(txId)), _.txHeight -> Some(liftQ(txHeight)))
    }
    run(q)
    log.info(s"Payout #$id transaction id set to $txId at $txHeight")
  }

  def confirmPayout(id: Int): Unit = {
    val q = quote {
      query[Payout].filter(_.id == id).update(_.confirmed -> true)
    }
    run(q)
    log.info(s"Payout confirmed: #$id")
  }
}
