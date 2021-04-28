package io.github.mkotsur.artc

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.{Deferred, Ref, TryableDeferred}

import scala.util.Try

sealed trait State[+T]

object State {

  /**
    * This state means that no syncs have been scheduled yet.
    */
  case class Init[A](firstDfrd: TryableDeferred[IO, Try[A]]) extends State[A]

  /**
    * Sync is happening at the moment, and there may or may not be a value available from the
    * previous sync.
    */
  case class Syncing[A](
    round: SyncRound,
    skips: Int,
    current: Option[Value[A]],
    nextDfrd: TryableDeferred[IO, Try[A]]
  ) extends State[A]

  /**
    * The cache contains a value from the previous update, and there is no
    * in progress sync happening.
    */
  case class Value[A](round: SyncRound, v: Try[A]) extends State[A]

}
