package rhttpc.transport.inmem

import akka.actor.{ActorRef, ActorSystem}
import rhttpc.transport.{DelayedMessage, Message, Publisher}

import scala.concurrent.Future

private[inmem] class InMemPublisher[Msg](queueActor: ActorRef)
                                        (implicit system: ActorSystem) extends Publisher[Msg] {

  import system.dispatcher

  override def publish(msg: Message[Msg]): Future[Unit] = {
    msg match {
      case delayed@DelayedMessage(_, delay, _) =>
        system.scheduler.scheduleOnce(delay, queueActor, delayed)
      case _ =>
        queueActor ! msg
    }
    Future.successful(Unit)
  }

  override def start(): Unit = {}

  override def stop(): Future[Unit] =
    Future.successful(Unit)

}