package io.github.mkotsur.artc

import cats.effect.concurrent.{Deferred, Ref, TryableDeferred}
import cats.effect.{ContextShift, Fiber, IO, Timer}
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.mkotsur.artc.Cache._
import io.github.mkotsur.artc.config.ColdReadPolicy
import io.github.mkotsur.artc.config.ColdReadPolicy.{Blocking, ReadThrough}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.Try

object Cache {

  private val logger = Slf4jLogger.getLogger[IO]

  object Settings {}
  case class Settings(ceilingInterval: FiniteDuration,
                      coldReadPolicy: ColdReadPolicy)

  object SyncRound {
    val first: SyncRound = SyncRound(0)
  }

  case class SyncRound private (value: Int) {
    def next: SyncRound = copy(value = value + 1)
  }

  /**
    * The value the cache will be initialized with
    */
  def create[T](settings: Settings, fetchValue: IO[T])(
    implicit cs: ContextShift[IO]
  ): IO[Cache[T]] =
    for {
      deferred <- Deferred.tryable[IO, Try[T]]
      sharesRef <- Ref.of[IO, TryableDeferred[IO, Try[T]]](deferred)
      syncRoundRef <- Ref.of[IO, SyncRound](SyncRound.first)
      _ <- logger.debug(s"Successfully configured Cache")
    } yield new Cache(sharesRef, syncRoundRef, settings, fetchValue)
}

final class Cache[T] private (valueRef: Ref[IO, TryableDeferred[IO, Try[T]]],
                              currentAttemptRef: Ref[IO, SyncRound],
                              settings: Settings,
                              fetch: IO[T])(implicit cs: ContextShift[IO]) {

  private def backoffInterval(a: SyncRound) =
    math
      .min(math.pow(2, a.value).toLong, settings.ceilingInterval.toSeconds)
      .seconds

  private def updateOnce(): IO[Unit] =
    logger.debug("Starting a new update") >> fetch.attempt.flatMap {
      fetchResult =>
        for {
          newDef <- Deferred.tryable[IO, Try[T]]
          oldDef <- valueRef.get
          _ <- oldDef.complete(fetchResult.toTry)
          _ <- valueRef.set(newDef)
        } yield ()
    } >> logger.debug("Update complete")

  def latest: IO[Option[T]] = {
    logger.debug(
      s"Providing the latest element with ${settings.coldReadPolicy}"
    ) >>
      (settings.coldReadPolicy match {
        case Blocking =>
          for {
            dd <- valueRef.get
            _ <- logger.debug(s"Old value: $dd")
            resultTry <- dd.get
            result <- IO.fromTry(resultTry)
          } yield result.some
        case ReadThrough =>
          for {
            dd <- valueRef.get
            resultOptTry <- dd.tryGet
            result <- resultOptTry match {
              case Some(resultTry) => IO.fromTry(resultTry).map(_.some)
              case None            => Option.empty[T].pure[IO]
            }
          } yield result
      })

  }

  def reset(): IO[Unit] =
    for {
      _ <- logger.debug(s"Reset refresh interval and update at once")
      _ <- currentAttemptRef.set(SyncRound.first)
      _ <- updateOnce()
    } yield ()

  def scheduleUpdates(implicit timer: Timer[IO]): IO[Fiber[IO, Unit]] =
    (for {
      _ <- updateOnce()
      _ <- currentAttemptRef.update(_.next)
      nextAttempt <- currentAttemptRef.get
      _ <- logger.debug(
        s"Scheduling new shares update in ${backoffInterval(nextAttempt)}"
      )
      _ <- timer.sleep(backoffInterval(nextAttempt)) *> scheduleUpdates
    } yield ()).start
}
