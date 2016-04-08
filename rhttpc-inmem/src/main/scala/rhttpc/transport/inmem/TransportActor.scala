package rhttpc.transport.inmem

import akka.actor.{Actor, Props, Status}

import scala.util.control.NonFatal

private class TransportActor(queueActorProps: => Props) extends Actor {

  override def receive: Receive = {
    case GetOrCreateQueue(name) =>
      try {
        val ref = context.child(name).getOrElse(context.actorOf(queueActorProps, name))
        sender() ! ref
      } catch {
        case NonFatal(ex) =>
          sender() ! Status.Failure(ex)
      }
  }

}

object TransportActor {
  def props(queueActorProps: => Props): Props =
    Props(new TransportActor(queueActorProps))
}

private[inmem] case class GetOrCreateQueue(name: String)