package io.github.mkotsur.artc

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.mkotsur.artc.config.ColdReadPolicy

import scala.collection._
import scala.concurrent.Promise

class CacheTest extends AsyncIOSpec with Matchers {

  private val logger = Slf4jLogger.getLogger[IO]

  "ART cache" - {
    "(With blocking cold read policy)" - {
      "blocks and returns the value" in {
        val settings =
          Cache.Settings(100 millis, ColdReadPolicy.Blocking)
        val readSource = logger.debug("Source accessed") >> IO.sleep(1 second) >> IO
          .pure(42)

        (for {
          cache <- Cache.create(settings, readSource)
          _ <- cache.scheduleUpdates
          latest <- cache.latest
        } yield latest)
          .asserting(_ shouldBe 42.some)
      }
    }

    "(with read through cold read policy)" - {
      val settings =
        Cache.Settings(100 millis, ColdReadPolicy.ReadThrough)
      "returns the zero element when no updates have been done" in {
        val readSource = IO.sleep(1 second) >> IO.pure(42)

        Cache
          .create(settings, readSource)
          .flatMap(_.latest)
          .asserting(_ shouldBe None)
      }

      "blocks until the data is available" in {
        val readSource = IO.sleep(1 second) >> IO.pure(42)

        Cache
          .create(settings, readSource)
          .flatMap(_.latest)
          .asserting(_ shouldBe None)
      }
      "returns constant element when an update should have been done" in {
        val readSource = IO.pure(42)

        (for {
          cache <- Cache.create(settings, readSource)
          updatesFiber <- cache.scheduleUpdates
          _ <- IO.sleep(100 millis)
          res <- cache.latest
          _ <- updatesFiber.cancel
        } yield res).asserting(_ shouldBe Some(42))
      }

      "returns fresh elements" in {
        val q = mutable.Queue.from(Range(1, 3))
        val readSource = IO(q.dequeue()).recover { _ =>
          4
        }

        (for {
          cache <- Cache.create(settings, readSource)
          updatesFiber <- cache.scheduleUpdates
          _ <- IO.sleep(300 millis)
          res <- cache.latest
          _ <- updatesFiber.cancel
        } yield res).asserting(_.get should be >= 2)
      }

      "returns a failure when the first update action fails" in {
        val error = new RuntimeException("Oops")
        val readSource = IO.raiseError[Int](error)

        (for {
          cache <- Cache.create(settings, readSource)
          updatesFiber <- cache.scheduleUpdates
          _ <- IO.sleep(300 millis)
          res <- cache.latest
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
          cache <- Cache.create(settings, readSource)
          updatesFiber <- cache.scheduleUpdates
          _ <- IO.sleep(300 millis)
          _ <- cache.latest.asserting(_ shouldEqual 42.some)
          _ <- IO.fromFuture(IO(failureRead.future))
          _ <- updatesFiber.cancel
          _ <- IO.sleep(300 millis)
          v2 <- cache.latest
        } yield v2).assertThrows[ReadSourceFailure]
      }
    }
  }
}
