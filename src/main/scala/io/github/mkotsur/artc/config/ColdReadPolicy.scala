package io.github.mkotsur.artc.config

import scala.concurrent.duration.FiniteDuration

/**
  * This ADT contains possible configuration values wrt the "cold read",
  * when the client requests the data which is not in cache yet.
  */
sealed trait ColdReadPolicy

object ColdReadPolicy {

  /**
    * Block and return once the data is available
    */
  case class Blocking(timeout: FiniteDuration) extends ColdReadPolicy

  /**
    * Immediately return
    */
  case object Reactive extends ColdReadPolicy
}
