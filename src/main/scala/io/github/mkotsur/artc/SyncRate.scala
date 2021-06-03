package io.github.mkotsur.artc

import cats.effect.IO
import io.github.mkotsur.artc.Cache.Settings

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.concurrent.duration._

object SyncRate {

  private[artc] def backoffInterval(settings: Settings)(a: SyncRound) = {
    val maxRound =
      Math.floor(Math.log(settings.ceilingInterval / settings.delayFactor) / Math.log(2)) + 1
    val power = Math.min(maxRound, a.value)
    val l = math
      .min(
        math.pow(2, power).toLong * settings.delayFactor.toMillis,
        settings.ceilingInterval.toMillis
      )
    l.millis
  }

  private[artc] def nextUpdateAt(settings: Settings, round: SyncRound): IO[LocalDateTime] =
    IO {
      LocalDateTime
        .now()
        .plus(backoffInterval(settings)(round).toMillis, ChronoUnit.MILLIS)
    }

}
