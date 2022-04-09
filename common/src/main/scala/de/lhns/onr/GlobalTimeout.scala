package de.lhns.onr

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object GlobalTimeout {
  val timeout: FiniteDuration = 5.minutes
}
