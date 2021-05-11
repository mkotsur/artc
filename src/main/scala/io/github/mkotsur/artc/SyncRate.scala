package io.github.mkotsur.artc

import cats.effect.IO
import io.github.mkotsur.artc.Cache.Settings

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.concurrent.duration._

object SyncRate {

  private[artc] def backoffInterval(settings: Settings)(a: SyncRound) =
    math
      .min(
        math.pow(2, a.value).toLong * settings.delayFactor.toMillis,
        settings.ceilingInterval.toMillis
      )
      .millis

  private[artc] def nextUpdateAt(settings: Settings, round: SyncRound): IO[LocalDateTime] =
    IO {
      LocalDateTime
        .now()
        .plus(backoffInterval(settings)(round).toMillis, ChronoUnit.MILLIS)
    }

}
