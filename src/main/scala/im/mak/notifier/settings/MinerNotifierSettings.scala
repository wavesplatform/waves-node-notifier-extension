package im.mak.notifier.settings

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader.arbitraryTypeValueReader
import net.ceedubs.ficus.readers.ValueReader
import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase

case class MinerNotifierSettings(
                                  webhook: WebhookSettings,
                                  blockUrl: String,
                                  mrtId: String,
                                  notifications: NotificationsSettings
                                )

case class WebhookSettings(
                            url: String,
                            method: String,
                            headers: Seq[String],
                            body: String
                          )

case class NotificationsSettings(
                                  startStop: Boolean,
                                  mrtReceived: Boolean,
                                  wavesReceived: Boolean,
                                  leasing: Boolean
                                )

object MinerNotifierSettings {
  implicit val valueReader: ValueReader[MinerNotifierSettings] = arbitraryTypeValueReader
}

object WebhookSettings {
  implicit val valueReader: ValueReader[WebhookSettings] = arbitraryTypeValueReader
}

object NotificationsSettings {
  implicit val valueReader: ValueReader[NotificationsSettings] = arbitraryTypeValueReader
}
