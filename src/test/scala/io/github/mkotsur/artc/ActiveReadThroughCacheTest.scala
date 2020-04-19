package io.github.mkotsur.artc

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps
import cats.implicits._

import scala.collection._
import scala.concurrent.Promise

class ActiveReadThroughCacheTest extends AsyncIOSpec with Matchers {

  private val settings = ActiveReadThroughCache.Settings(1 milli, 100 millis)

  "ART cache" - {
    "returns zero element when no updates have been done" in {
      val readSource = IO.sleep(1 second) >> IO.pure(42)
      ActiveReadThroughCache
        .create(settings, readSource)
        .flatMap(_.mostRecent)
        .asserting(_ shouldBe None)
    }
    "returns constant element when an update should have been done" in {
      val readSource = IO.pure(42)

      (for {
        cache <- ActiveReadThroughCache.create(settings, readSource)
        updatesFiber <- cache.scheduleUpdates
        _ <- IO.sleep(100 millis)
        res <- cache.mostRecent
        _ <- updatesFiber.cancel
      } yield res).asserting(_ shouldBe Some(42))
    }

    "returns fresh elements" in {
      val q = mutable.Queue.from(Range(1, 3))
      val readSource = IO(q.dequeue()).recover { _ =>
        4
      }

      (for {
        cache <- ActiveReadThroughCache.create(settings, readSource)
        updatesFiber <- cache.scheduleUpdates
        _ <- IO.sleep(300 millis)
        res <- cache.mostRecent
        _ <- updatesFiber.cancel
      } yield res).asserting(_.get should be >= 2)
    }

    "returns a failure when the first update action fails" in {
      val error = new RuntimeException("Oops")
      val readSource = IO.raiseError[Int](error)

      (for {
        cache <- ActiveReadThroughCache.create(settings, readSource)
        updatesFiber <- cache.scheduleUpdates
        _ <- IO.sleep(300 millis)
        res <- cache.mostRecent
        _ <- updatesFiber.cancel
      } yield res).assertThrows[ReadSourceFailure]
    }

    "returns a failure when a subsequent update action fails" in {
      val error = new RuntimeException("Oops")
      val successRead = Promise[Unit]
      val failureRead = Promise[Unit]
      val readSource = IO {
        if (successRead.isCompleted) {
          failureRead.success(())
          throw error
        } else {
          successRead.success(())
          42
        }
      }
      (for {
        cache <- ActiveReadThroughCache.create(settings, readSource)
        updatesFiber <- cache.scheduleUpdates
        _ <- IO.sleep(300 millis)
        _ <- cache.mostRecent.asserting(_ shouldEqual 42.some)
        _ <- IO.fromFuture(IO(failureRead.future))
        _ <- updatesFiber.cancel
        _ <- IO.sleep(300 millis)
        v2 <- cache.mostRecent
      } yield v2).assertThrows[ReadSourceFailure]
    }
  }
}

