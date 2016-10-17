package rhttpc.transport

import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.language.postfixOps

class DelayedMessageSpec extends FlatSpec with Matchers {

  it should "extract delayed message from properties in various numeric format" in {
    val DelayedMessage(_, fromLongDuration, _) = Message("fooMsg", Map(MessagePropertiesNaming.delayProperty -> 100L))
    fromLongDuration shouldEqual (100 millis)
    val DelayedMessage(_, fromIntDuration, _) = Message("fooMsg", Map(MessagePropertiesNaming.delayProperty -> 100))
    fromIntDuration shouldEqual (100 millis)
  }

}
