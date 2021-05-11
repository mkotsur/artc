package io.github.mkotsur.artc

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.github.mkotsur.artc.config.ColdReadPolicy
import org.scalatest.exceptions.TestFailedException
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.matchers.should.Matchers

import java.util.Date
import scala.collection._
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class CacheTest extends AsyncFunSpec with AsyncIOSpec with Matchers {

  private val logger = Slf4jLogger.getLogger[IO]

  describe("ART cache") {
    describe("(With blocking cold read policy)") {
      val settings =
        Cache.Settings(5000 millis, 1000, 100 millis, ColdReadPolicy.Blocking(1 second))
      it("blocks and returns the first value") {
        val readSource = IO.sleep(1 second) >> IO.pure(42)

        Cache
          .create(settings, readSource)
          .use(_.latest)
          .asserting(_ shouldBe 42.some)
      }

      it("returns the available value") {
        val source = mutable.Queue.newBuilder.addOne(42).result()
        // Returns: 1, ... ZzzzzzZZZzzzZZZZZzzzzz
        val fetchValue = IO(source.dequeue()).handleErrorWith(_ => IO.sleep(1 minute))

        Cache
          .create(settings, fetchValue)
          .use(_.latest)
          .asserting(_ shouldBe 42.some)
      }

      it("returns a failure when fetch waits and fails") {
        val fetchValue = IO.sleep(300 millis) >> IO.raiseError[Int](new RuntimeException("Oops"))

        Cache
          .create(settings, fetchValue)
          .use { _.latest }
          .assertThrows[ReadSourceFailure]
      }

      it("returns a failure when fetch fails immediately") {
        val fetchValue = IO.raiseError[Int](new RuntimeException("Oops"))

        Cache
          .create(settings, fetchValue)
          .use { cache =>
            IO.sleep(300 millis) >> cache.latest
          }
          .assertThrows[ReadSourceFailure]
      }
    }

    describe("(With reactive cold read policy)") {
      val settings =
        Cache.Settings(100 millis, 1000, 100 millis, ColdReadPolicy.Reactive)

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
            (IO.sleep(300 millis) >> cache.latest).assertThrows[ReadSourceFailure]
          }
          .assertNoException
      }

      it("returns a failure when a subsequent update action fails") {
        val successReadLatchP = Promise[Unit]
        val fetchValue = IO {
          if (!successReadLatchP.isCompleted) {
            42 // First return success
          } else {
            throw new RuntimeException("Oops") // Then fail
          }
        }

        Cache
          .create(settings, fetchValue)
          .use { cache =>
            for {
              _ <- IO.sleep(300 millis)
              _ <- cache.latest.asserting(_ shouldEqual 42.some)
              _ <- IO(successReadLatchP.success(())) // Move to next state
              _ <- IO.sleep(300 millis)
              _ <- cache.latest.asserting(_ shouldEqual 42.some)
            } yield ()
          }
          .assertNoException
      }

      it("returns the latest cached value while an update is in progress") {
        val firstReadLatchP = Promise[Unit]
        val fetchValue: IO[Int] = for {
          firstCompleted <- IO(firstReadLatchP.isCompleted)
          v <-
            if (!firstCompleted) {
              IO(println("Return 42")) *> IO(firstReadLatchP.success()) *> IO.pure(42)
            } else {
              IO(println("Blocking forever")) *> IO.never *> IO(
                println("This should never happen")
              ) *> IO.never
            }
        } yield v

        Cache
          .create(settings, fetchValue)
          .use { cache =>
            for {
              _ <- IO.sleep(300 millis)
              _ <- IO(println("Going to assert"))
              _ <- cache.latest.asserting(_ shouldEqual 42.some)
              _ <- IO(println("First check passed"))
              _ <- IO.sleep(300 millis)
              _ <- cache.latest.asserting(_ shouldEqual 42.some)
            } yield ()

          }
          .assertNoException
      }

      it("should increase update interval") {
        val fetchValue = IO {
          new Date().getTime
        }

        val theSettings = settings.copy(ceilingInterval = 5 seconds, delayFactor = 100)
        Cache
          .create(theSettings, fetchValue)
          .use { cache =>
            for {
              testStartMillis <- fetchValue // get millis without cache
              firstAccessMillis <- cache.latest.flatMap { // access first time
                case None                    => IO.raiseError(new RuntimeException("Value should have been available"))
                case Some(firstAccessMillis) => firstAccessMillis.pure[IO]
              }
              _ <- IO(firstAccessMillis - testStartMillis)
                .asserting(_ should be <= theSettings.delayFactor.toLong)
              _ <- IO.sleep(theSettings.ceilingInterval * 3)
              secondAccessMillis <- cache.latest.flatMap {
                case None                    => IO.raiseError(new RuntimeException("Value should have been available"))
                case Some(firstAccessMillis) => firstAccessMillis.pure[IO]
              }
              _ <- IO(new Date().getTime - secondAccessMillis)
                .asserting(_ should be > theSettings.delayFactor.toLong)
            } yield ()
          }
          .assertNoException
      }

      it("should reset update interval") {

        val theSettings = settings.copy(ceilingInterval = 2 seconds, delayFactor = 100)
        Ref
          .of[IO, List[Long]](Nil)
          .flatMap(allUpdatesRef => {
            val fetchValue = allUpdatesRef.update(_ :+ new Date().getTime)

            Cache
              .create(theSettings, fetchValue)
              .use {
                cache =>
                  for {
                    _ <-
                      IO.sleep(
                        theSettings.ceilingInterval
                      ) // at least 2 updates should have happened
                    _ <- cache.latest
                    _ <- IO.sleep(3 seconds)
                    _ <-
                      allUpdatesRef.get
                        .map { updateTimestamps =>
                          def d: PartialFunction[List[Long], List[Long]] = {
                            case vals @ _ :: vv =>
                              vv.zip(vals).map { case (prev, next) => prev - next }
                          }

                          val updateIntervals = d(updateTimestamps)
                          val updateDeltas = d(updateIntervals)
                          updateDeltas.filter(_ < 0)
                        }
                        .asserting(_ should not be empty)
                    _ <- IO(println("Done"))
                  } yield ()
              }
              .assertNoException
          })
      }
    }
  }
}
