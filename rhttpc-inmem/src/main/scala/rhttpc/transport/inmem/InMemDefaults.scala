package rhttpc.transport.inmem

import scala.concurrent.duration.{FiniteDuration, _}

object InMemDefaults extends InMemDefaults

trait InMemDefaults {
  private[rhttpc] val createTimeout: FiniteDuration = 5.seconds

  private[rhttpc] val stopConsumingTimeout: FiniteDuration = 5.seconds

  private[rhttpc] val stopTimeout: FiniteDuration = 5.seconds
}
