package io.github.mkotsur.artc

import cats.effect.concurrent.{Deferred, Ref, TryableDeferred}
import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.mkotsur.artc.Cache._
import io.github.mkotsur.artc.State.{Init, Synced, Syncing}
import io.github.mkotsur.artc.SyncRate.backoffInterval
import io.github.mkotsur.artc.config.ColdReadPolicy

import scala.concurrent.duration._
import scala.language.postfixOps
import java.time.LocalDateTime
import java.time.temporal.{ChronoUnit}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

object Cache {

  private val logger = Slf4jLogger.getLogger[IO]

  case class Settings(
    /**
      * This value puts a cap on the exponential growth of the
      * refresh interval between the rounds.
      */
    ceilingInterval: FiniteDuration,
    /**
      * This factor indicates how long to wait after round 0,
      * and is subsequently used as a multiplier for `2^round`.
      * E.g.:
      * Round: 0; Refresh interval: `delayFactor`;
      * Round: 1; Refresh interval: `2 * delayFactor`;
      * Round: 2; Refresh interval: `4 * delayFactor`;
      * Round: 3; Refresh interval: `8 * delayFactor`, and so on.
      */
    delayFactor: FiniteDuration = 1 second,
    /**
      * How often will the cache check if the update needs to occur.
      * This value should never be larger than delayFactor.
      */
    tickInterval: FiniteDuration = 100 millis,
    coldReadPolicy: ColdReadPolicy = ColdReadPolicy.Reactive
  )

  /**
    * Creates an instance of [[Cache]]
    */
  def create[T](settings: Settings, fetchValue: IO[T])(implicit
    cs: ContextShift[IO],
    timer: Timer[IO]
  ): Resource[IO, Cache[T]] = {

    def nextUpdateAt(round: SyncRound): IO[LocalDateTime] =
      IO {
        LocalDateTime
          .now()
          .plus(backoffInterval(settings)(round).toMillis, ChronoUnit.MILLIS)
      }

    def cacheUpdateIO(stateRef: Ref[IO, State[T]]): IO[Unit] =
      for {
        _ <- logger.debug("Cache update started")
        state <- stateRef.get
        _ <- state match {
          case Init(updateDeferred: TryableDeferred[IO, Try[T]]) =>
            logger.debug("... from Init state") *>
              (for {
                _ <- stateRef.set(Syncing(SyncRound.zero, None, updateDeferred))
                valueEither <- fetchValue.attempt
                _ <- updateDeferred.complete(valueEither.toTry)
                nextUpdateAt <- nextUpdateAt(SyncRound.zero)
                _ <- stateRef.update(_ => Synced(SyncRound.zero, valueEither.toTry, nextUpdateAt))
              } yield ())
          case Synced(round, storedValue: Try[T], nextUpdate)
              if LocalDateTime.now().isAfter(nextUpdate) =>
            logger.debug(
              s"... from Synced state because ${LocalDateTime.now()} is after ${nextUpdate}"
            ) *>
              (for {
                updateDeferred <- Deferred.tryable[IO, Try[T]]
                _ <- stateRef.set(Syncing[T](round.next, storedValue.some, updateDeferred))
                fetchedValueEither <- fetchValue.attempt
                _ <- updateDeferred.complete(fetchedValueEither.toTry)
                _ <- logger.debug("Preparing to set new value")
                nextUpdateAt <- nextUpdateAt(round.next)
                _ <- stateRef.update(
                  _ =>
                    Synced(
                      round.next,
                      (fetchedValueEither.toTry, storedValue) match {
                        case (Failure(_), Success(_)) => storedValue
                        case _                        => fetchedValueEither.toTry
                      },
                      nextUpdateAt
                    )
                )
                _ <- logger.debug(s"Set new value and schedule update at $nextUpdateAt")
              } yield ())
          case Synced(_, _, nextUpdate) =>
            logger.trace(s"Skipping as it is not the right time yet.")
          case _: Syncing[T] =>
            logger.debug("Skipping a new update, because there is one in progress...")
        }
      } yield ()

    def scheduleCacheUpdate(ref: Ref[IO, State[T]]): IO[Unit] =
      cacheUpdateIO(ref) >> timer.sleep(settings.tickInterval) >> scheduleCacheUpdate(ref)

    for {
      updateDeferred <- Resource.liftF(Deferred.tryable[IO, Try[T]])
      initState = Init(updateDeferred)
      initStateRef <- Resource.liftF(Ref.of[IO, State[T]](initState))
      _ <- Resource.make(scheduleCacheUpdate(initStateRef).start)(_.cancel)
    } yield new Cache(initStateRef, settings)
  }

}

final class Cache[T] private (stateRef: Ref[IO, State[T]], settings: Settings)(implicit
  cs: ContextShift[IO]
) {

  def latest: IO[Option[T]] =
    logger.debug("Accessing latest value") >> stateRef
      .updateAndGet {
        case s: Syncing[T] => s.copy(round = SyncRound.zero)
        case s: Synced[T]  => s.copy(round = SyncRound.zero)
        case s             => s
      }
      .flatMap {
        case Init(firstValDeferred) =>
          logger.debug("Accessing cache while in Init state") >> (settings.coldReadPolicy match {
            case ColdReadPolicy.Reactive => None.pure[IO]
            case ColdReadPolicy.Blocking(_) =>
              firstValDeferred.get.flatMap(
                t => IO.fromEither(t.map(_.some).toEither.left.map(ReadSourceFailure))
              )
          })
        case Syncing(_, None, next) =>
          logger.debug("Accessing cache while in Syncing(None) state") >>
            (settings.coldReadPolicy match {
              case ColdReadPolicy.Reactive => None.pure[IO]
              case ColdReadPolicy.Blocking(_) =>
                next.get
                  .flatMap(t => IO.fromEither(t.toEither.left.map(ReadSourceFailure)))
                  .map(_.some)
            })
        case Syncing(_, Some(current), next) =>
          logger.debug("Accessing cache while in Syncing(Some()) state") >>
            (settings.coldReadPolicy match {
              case ColdReadPolicy.Reactive    => IO.fromTry(current).map(_.some)
              case ColdReadPolicy.Blocking(_) => next.get.map(_.toOption)
            })
        case Synced(_, v, _) =>
          IO.fromEither(v.toEither.left.map(ReadSourceFailure)).map(_.some)
      }

}
