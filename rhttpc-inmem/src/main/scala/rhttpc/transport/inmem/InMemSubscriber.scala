package rhttpc.transport.inmem

import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import rhttpc.transport.Subscriber

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class InMemSubscriber[Msg](queueActor: ActorRef,
                           consumer: ActorRef,
                           fullMessage: Boolean)
                          (stopTimeout: FiniteDuration) extends Subscriber[Msg] {

  override def start(): Unit = {
    queueActor ! RegisterConsumer(consumer, fullMessage)
  }

  override def stop(): Future[Unit] = {
    implicit val timeout = Timeout(stopTimeout)
    (queueActor ? UnregisterConsumer(consumer)).mapTo[Unit]
  }

}