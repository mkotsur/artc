package io.github.mkotsur.artc

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.mkotsur.artc.config.ColdReadPolicy
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.matchers.should.Matchers

import scala.collection._
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.language.postfixOps

class CacheTest extends AsyncFunSpec with AsyncIOSpec with Matchers {

  private val logger = Slf4jLogger.getLogger[IO]

  describe("ART cache") {
    describe("(With blocking cold read policy)") {
      it("blocks and returns the first value") {
        val settings =
          Cache.Settings(100 millis, ColdReadPolicy.Blocking(1 second))
        val readSource = IO.sleep(1 second) >> IO.pure(42)

        Cache
          .create(settings, readSource)
          .use(_.latest)
          .asserting(_ shouldBe 42.some)
      }

      it("returns the available value") {
        val settings =
          Cache.Settings(100 millis, ColdReadPolicy.Blocking(1 second))

        val source = mutable.Queue.newBuilder.addOne(42).result()
        // Returns: 1, ... ZzzzzzZZZzzzZZZZZzzzzz
        val fetchValue = IO(source.dequeue()).handleErrorWith(_ => IO.sleep(1 minute))

        Cache
          .create(settings, fetchValue)
          .use(_.latest)
          .asserting(_ shouldBe 42.some)
      }

      it("returns a failure when the first update is awaited and has a cached failed value") {
        val fetchValue = IO.sleep(300 millis) >> IO.raiseError[Int](new RuntimeException("Oops"))
        val settings =
          Cache.Settings(100 millis, ColdReadPolicy.Blocking(1 second))

        Cache
          .create(settings, fetchValue)
          .use { _.latest }
          .assertThrows[ReadSourceFailure]
      }
    }

    it("returns a failure when the first update has a cached failed value") {
      val fetchValue = IO.raiseError[Int](new RuntimeException("Oops"))
      val settings =
        Cache.Settings(100 millis, ColdReadPolicy.Blocking(1 second))

      Cache
        .create(settings, fetchValue)
        .use { cache =>
          IO.sleep(300 millis) >> cache.latest
        }
        .assertThrows[ReadSourceFailure]
    }

    describe("(With reactive cold read policy)") {
      val settings =
        Cache.Settings(100 millis, ColdReadPolicy.Reactive)

      it("returns the zero element when no updates have been done") {
        val readSource = IO.sleep(1 second) >> IO.pure(42)

        Cache
          .create(settings, readSource)
          .use(_.latest)
          .asserting(_ shouldBe None)
      }

      it("returns constant element when an update should have been done") {
        val readSource = IO.pure(42)

        Cache
          .create(settings, readSource)
          .use { cache =>
            IO.sleep(150 millis) >> cache.latest
          }
          .asserting(_ shouldBe Some(42))

      }

      it("returns fresh elements") {
        val source = mutable.Queue.from(Range(1, 3))
        // Returns: 1, 2, 3, 4, 4, ...
        val fetchValue = IO(source.dequeue()).recover(_ => 4)

        Cache
          .create(settings, fetchValue)
          .use { cache =>
            IO.sleep(300 millis) >> cache.latest
          }
          .asserting(_.get should be >= 2)
      }

      it("returns a failure when the first update action fails") {
        val error = new RuntimeException("Oops")
        val fetchValue = IO.raiseError[Int](error)

        Cache
          .create(settings, fetchValue)
          .use { cache =>
            IO.sleep(300 millis) >> cache.latest
          }
          .assertThrows[ReadSourceFailure]
      }

      it("returns a failure when a subsequent update action fails") {
        val error = new RuntimeException("Oops")
        val successRead = Promise[Unit]
        val failureRead = Promise[Unit]
        val fetchValue = IO {
          if (successRead.isCompleted) {
            failureRead.success(())
            throw error
          } else {
            successRead.success(())
            42
          }
        }

        Cache
          .create(settings, fetchValue)
          .use { cache =>
            for {
              _ <- IO.sleep(300 millis)
              _ <- cache.latest.asserting(_ shouldEqual 42.some)
              _ <- IO.fromFuture(IO(failureRead.future))
              _ <- IO.sleep(300 millis)
              v2 <- cache.latest
            } yield v2

          }
          .assertThrows[ReadSourceFailure]
      }
    }
  }
}
