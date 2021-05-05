package io.github.mkotsur.artc

import cats.effect.concurrent.{Deferred, Ref, TryableDeferred}
import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.mkotsur.artc.Cache._
import io.github.mkotsur.artc.State.{Init, Syncing, Value}
import io.github.mkotsur.artc.SyncRate.backoffInterval
import io.github.mkotsur.artc.config.ColdReadPolicy

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

object Cache {

  private val logger = Slf4jLogger.getLogger[IO]

  object Settings {}
  case class Settings(ceilingInterval: FiniteDuration, coldReadPolicy: ColdReadPolicy)

  /**
    * Creates an instance of [[Cache]]
    */
  def create[T](settings: Settings, fetchValue: IO[T])(implicit
    cs: ContextShift[IO],
    timer: Timer[IO]
  ): Resource[IO, Cache[T]] = {

    def cacheUpdateIO(stateRef: Ref[IO, State[T]]): IO[Unit] =
      for {
        _ <- logger.debug("Cache update started")
        state <- stateRef.get
        _ <- state match {
          case Init(updateDeferred: TryableDeferred[IO, Try[T]]) =>
            logger.debug("... from Init state") *>
              (for {
                _ <- stateRef.set(Syncing(SyncRound.zero, 0, None, updateDeferred))
                valueEither <- fetchValue.attempt
                _ <- updateDeferred.complete(valueEither.toTry)
                _ <- stateRef.update(_ => Value(SyncRound.zero, valueEither.toTry))
                _ <-
                  timer.sleep(backoffInterval(settings)(SyncRound.zero)) *> cacheUpdateIO(stateRef)
              } yield ())
          case v @ Value(round, storedValue: Try[T]) =>
            logger.debug("... from Value state") *>
              (for {
                updateDeferred <- Deferred.tryable[IO, Try[T]]
                _ <- stateRef.set(
                  Syncing[T](round.next, 0, Value(round, storedValue).some, updateDeferred)
                )
                fetchedValueEither <- fetchValue.attempt
                _ <- updateDeferred.complete(fetchedValueEither.toTry)
                _ <- logger.debug("Preparing to set new value")
                _ <- stateRef.update(
                  _ =>
                    Value(
                      round.next,
                      (fetchedValueEither.toTry, storedValue) match {
                        case (Failure(_), Success(_)) => storedValue
                        case _                        => fetchedValueEither.toTry
                      }
                    )
                )
                _ <- logger.debug("Set new value")
                _ <- timer.sleep(backoffInterval(settings)(round.next)) *> cacheUpdateIO(stateRef)
              } yield ())
          case _: Syncing[T] =>
            logger.debug("Skipping a new update, because there is one in progress...")
        }
      } yield ()

    for {
      updateDeferred <- Resource.liftF(Deferred.tryable[IO, Try[T]])
      initState = Init(updateDeferred)
      initStateRef <- Resource.liftF(Ref.of[IO, State[T]](initState))
      _ <- Resource.make(cacheUpdateIO(initStateRef).start)(fiber => fiber.cancel)
    } yield new Cache(initStateRef, settings, fetchValue)
  }

}

final class Cache[T] private (stateRef: Ref[IO, State[T]], settings: Settings, fetch: IO[T])(
  implicit cs: ContextShift[IO]
) {

  def latest: IO[Option[T]] =
    stateRef.get.flatMap {
      case Init(firstValDeferred) =>
        logger.debug("Accessing cache while in Init state") >> (settings.coldReadPolicy match {
          case ColdReadPolicy.Reactive => None.pure[IO]
          case ColdReadPolicy.Blocking(_) =>
            firstValDeferred.get.flatMap(
              t => IO.fromEither(t.map(_.some).toEither.left.map(ReadSourceFailure))
            )
        })
      case Syncing(round, skips, None, next) =>
        logger.debug("Accessing cache while in Syncing(None) state") >>
          (settings.coldReadPolicy match {
            case ColdReadPolicy.Reactive => None.pure[IO]
            case ColdReadPolicy.Blocking(_) =>
              next.get
                .flatMap(t => IO.fromEither(t.toEither.left.map(ReadSourceFailure)))
                .map(_.some)
          })
      case Syncing(round, skips, Some(current), next) =>
        logger.debug("Accessing cache while in Syncing(Some()) state") >>
          (settings.coldReadPolicy match {
            case ColdReadPolicy.Reactive    => IO.fromTry(current.v).map(_.some)
            case ColdReadPolicy.Blocking(_) => next.get.map(_.toOption)
          })
      case Value(_, v) => IO.fromEither(v.toEither.left.map(ReadSourceFailure)).map(_.some)
    }

}
