package reliablehttpc.sample

import akka.actor._
import akka.pattern._
import akka.persistence._

class FooBarActor(id: String, client: DelayedEchoClient) extends PersistentActor with FSM[FooBarState, Unit.type] {
  
  override def persistenceId: String = "foobar-" + id

  import context.dispatcher
  
  startWith(InitState, Unit)

  when(InitState) {
    case Event(SendMsg(msg), _) =>
      client.requestResponse(msg) pipeTo self
      goto(WaitingForResponseState)
    case Event(CurrentState, _) =>
      sender() ! InitState
      stay()
  }
  
  when(WaitingForResponseState) {
    case Event("foo", _) => goto(FooState)
    case Event("bar", _) => goto(BarState)
    case Event(CurrentState, _) =>
      sender() ! WaitingForResponseState
      stay()
  }

  when(FooState) {
    case Event(CurrentState, _) =>
      sender() ! FooState
      stay()
  }

  when(BarState) {
    case Event(CurrentState, _) =>
      sender() ! BarState
      stay()
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, s: FooBarState) =>
      goto(s)
  }
  
  override def receiveCommand: Receive = {
    case _ => throw new IllegalArgumentException("Should be used receive of FSM")
  }

  onTransition {
    case (_, to) => saveSnapshot(to) 
  }
}

sealed trait FooBarState

case object InitState extends FooBarState
case object WaitingForResponseState extends FooBarState
case object FooState extends FooBarState
case object BarState extends FooBarState

case class SendMsg(msg: String)
case object CurrentState