package io.github.mkotsur.artc

import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, Fiber, IO, Timer}
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success, Try}

object ActiveReadThroughCache {

  case class Settings(initialInterval: FiniteDuration,
                      ceilingInterval: FiniteDuration)

  object IdleRefresh {
    val first: IdleRefresh = IdleRefresh(0)
  }

  case class IdleRefresh private (value: Int) {
    def next: IdleRefresh = copy(value = value + 1)
  }

  def create[T](settings: Settings, fetchValue: IO[T])(
    implicit cs: ContextShift[IO]
  ): IO[ActiveReadThroughCache[T]] =
    for {
      // We set an empty list as initial value and that means, that first to the cache will return a wrong
      // value in case when it happens before the first roundtrip ro ResearchDrive is completed.
      // Alternative (more complex solutions) include:
      // - Introducing a special value (e.g. returning Option[List[Share]], where None means: data not available yet;
      // - Leveraging [[cats.effect.concurrent.Deferred]] datatype.
      sharesRef <- Ref.of[IO, Try[Option[T]]](Success(None))
      refreshRateRef <- Ref.of[IO, IdleRefresh](IdleRefresh.first)
    } yield
      new ActiveReadThroughCache(
        sharesRef,
        refreshRateRef,
        settings,
        fetchValue
      )
}

import io.github.mkotsur.artc.ActiveReadThroughCache._

final class ActiveReadThroughCache[T] private (
  valueRef: Ref[IO, Try[Option[T]]],
  currentAttemptRef: Ref[IO, IdleRefresh],
  settings: Settings,
  readSource: IO[T]
)(implicit cs: ContextShift[IO]) {

  private val logger = Slf4jLogger.getLogger[IO]

  private def backoffInterval(a: IdleRefresh) =
    math
      .min(math.pow(2, a.value).toLong, settings.ceilingInterval.toSeconds)
      .seconds

  private def updateOnce(): IO[Unit] =
    logger.debug("Starting a new update") >> readSource.attempt.flatMap {
      case Right(newValue) =>
        valueRef.set(Success(newValue.some)) *> logger.debug(
          s"Updated cache with $newValue"
        )
      case Left(e) =>
        valueRef.set(Failure(ReadSourceFailure(e))) *>
          logger.error(e)("Could not read new value")
    }

  def mostRecent: IO[Option[T]] = valueRef.get.flatMap(IO.fromTry)

  def reset(): IO[Unit] =
    for {
      _ <- logger.debug(s"Reset refresh interval and update at once")
      _ <- currentAttemptRef.set(IdleRefresh.first)
      _ <- updateOnce()
    } yield ()

  def scheduleUpdates(implicit timer: Timer[IO],
                      cs: ContextShift[IO]): IO[Fiber[IO, Unit]] =
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
