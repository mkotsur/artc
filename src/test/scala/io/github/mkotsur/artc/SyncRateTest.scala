package io.github.mkotsur.artc

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class SyncRateTest extends AnyFunSpec with Matchers {

  describe("Sync State") {
    val settings =
      Cache.Settings(5 seconds, 1 second, 100 millis)

    it("should start with delay factor for round 0") {
      SyncRate.backoffInterval(settings)(SyncRound.zero) shouldEqual settings.delayFactor
    }

    it("should return ceiling interval for a very large round") {
      SyncRate.backoffInterval(settings)(
        SyncRound(Int.MaxValue)
      ) shouldEqual settings.ceilingInterval
    }
  }
}
