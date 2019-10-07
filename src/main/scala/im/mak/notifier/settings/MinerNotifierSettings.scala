package im.mak.notifier.settings

import net.ceedubs.ficus.readers.ArbitraryTypeReader.arbitraryTypeValueReader
import net.ceedubs.ficus.readers.ValueReader

case class MinerNotifierSettings(
    blockUrl: String,
    mrtId: String,
    notifications: NotificationsSettings,
    webhook: WebhookSettings,
    payout: PayoutSettings
)

object MinerNotifierSettings {
  implicit val valueReader: ValueReader[MinerNotifierSettings] = arbitraryTypeValueReader
}

case class WebhookSettings(
    url: String,
    method: String,
    headers: Seq[String],
    body: String
)

object WebhookSettings {
  implicit val valueReader: ValueReader[WebhookSettings] = arbitraryTypeValueReader
}

case class NotificationsSettings(
    startStop: Boolean,
    mrtReceived: Boolean,
    wavesReceived: Boolean,
    leasing: Boolean
)

object NotificationsSettings {
  implicit val valueReader: ValueReader[NotificationsSettings] = arbitraryTypeValueReader
}

case class PayoutSettings(
    enabled: Boolean,
    fromHeight: Int,
    interval: Int,
    percent: Int,
    additionalTokens: Seq[PayoutSettings.Token]
) {
  require(interval > 0, s"Invalid interval: $interval")
  require(percent > 0 && percent <= 100, s"Invalid payout percent: $percent")
}

object PayoutSettings {
  case class Token(assetId: String, amount: Long) {
    require(assetId.nonEmpty && amount > 0, s"Invalid token configuration: $this")
  }

  object Token {
    implicit val valueReader: ValueReader[Token] = arbitraryTypeValueReader
  }

  implicit val valueReader: ValueReader[PayoutSettings] = arbitraryTypeValueReader
}
