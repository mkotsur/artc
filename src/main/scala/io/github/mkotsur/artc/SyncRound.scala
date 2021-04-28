package io.github.mkotsur.artc

object SyncRound {
  val zero: SyncRound = SyncRound(0)
}

case class SyncRound private (value: Int) {
  def next: SyncRound = copy(value = value + 1)
}
