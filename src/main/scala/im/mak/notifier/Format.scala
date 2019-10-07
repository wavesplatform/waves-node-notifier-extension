package im.mak.notifier

import java.text.DecimalFormat

private[notifier] object Format {
  def mrt(tokens: Long): String = new DecimalFormat("###,###.##")
    .format(math.pow(tokens, -2))

  def waves(wavelets: Long): String = new DecimalFormat("###,###.########")
    .format(math.pow(wavelets, -8))
}
