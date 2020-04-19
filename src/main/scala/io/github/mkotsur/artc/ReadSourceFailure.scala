package io.github.mkotsur.artc

/**
  * This error indicates that the operation to the service failed.
  */
case class ReadSourceFailure(cause: Throwable)
    extends IllegalStateException("Value Read operation failed", cause)
