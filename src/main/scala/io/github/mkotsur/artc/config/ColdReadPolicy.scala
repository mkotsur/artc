package io.github.mkotsur.artc.config

/**
  * This ADT contains possible configuration values wrt the "cold read",
  * when the client requests the data which is not in cache yet.
  */
sealed trait ColdReadPolicy

object ColdReadPolicy {

  /**
    * Block and return once the data is available
    */
  case object Blocking extends ColdReadPolicy

  /**
    * Immediately return
    */
  case object ReadThrough extends ColdReadPolicy
}
