package io.github.mkotsur.artc

import io.github.mkotsur.artc.Cache.Settings

import scala.concurrent.duration._

object SyncRate {

  private[artc] def backoffInterval(settings: Settings)(a: SyncRound) =
    math
      .min(
        math.pow(2, a.value).toLong * settings.delayFactor.toMillis,
        settings.ceilingInterval.toMillis
      )
      .millis

}
