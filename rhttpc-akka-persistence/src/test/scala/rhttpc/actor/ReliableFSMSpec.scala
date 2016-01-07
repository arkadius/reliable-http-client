/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rhttpc.actor

import akka.actor.{ActorSystem, FSM, PoisonPill}
import akka.persistence._
import akka.testkit._
import org.scalatest._
import rhttpc.actor.impl._
import rhttpc.client.ReliableClientBaseSpec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class ReliableFSMSpec
  extends TestKit(ActorSystem("ReliableFSMSpec"))
  with ReliableClientBaseSpec with ImplicitSender with Matchers {

  it should "save snapshot after message publication and reply with StateSaved" in { fixture =>
    createAndSendFoo(fixture, "12")
  }

  it should "recover in correct state not saving snapshot one more time" in { fixture =>
    val id = "123"
    val actor = createAndSendFoo(fixture, id)
    val snapshot = actor.underlyingActor.savedSnapshots.head

    watch(actor)
    actor ! PoisonPill
    expectTerminated(actor)

    val recovered = TestActorRef[FooBarActor](FooBarActor.props(id, fixture.client))
    recovered ! NotifyAboutRecoveryCompleted
    recovered.underlyingActor.recover(snapshot)
    expectMsg(RecoveryCompleted)

    Thread.sleep(500)
    recovered.underlyingActor.savedSnapshots shouldBe empty

    fixture.transport.replySubscriptionPromise.success("foo")
    awaitCond(recovered.underlyingActor.savedSnapshots.size == 1)
    Await.result(fixture.transport.ackOnReplySubscriptionFuture, 3 seconds)

    recovered ! CurrentState
    expectMsg(FooState)
  }

  def createAndSendFoo(fixture: FixtureParam, id: String): TestActorRef[FooBarActor] = {
    val actor = TestActorRef[FooBarActor](FooBarActor.props(id, fixture.client))
    actor ! SendMsg("foo")
    Thread.sleep(500)
    actor.underlyingActor.savedSnapshots shouldBe empty
    fixture.transport.publicationPromise.success(Unit)
    awaitCond(actor.underlyingActor.savedSnapshots.size == 1)
    expectMsg(StateSaved)
    actor
  }
}

trait MockReliableFSM[S, D] extends AbstractReliableFSM[S, D] with MockSnapshotter[S, D]

trait MockSnapshotter[S, D] extends AbstractSnapshotter { this: FSM[S, D] =>
  @volatile var savedSnapshots: List[SnapshotWithSeqNr] = List.empty

  override def saveSnapshotWithSeqNr(snapshot: Any, seqNr: Long): Unit = {
    savedSnapshots = SnapshotWithSeqNr(snapshot, seqNr) :: savedSnapshots
    self ! SaveSnapshotSuccess(SnapshotMetadata(persistenceId, seqNr))
  }

  def recover(snap: SnapshotWithSeqNr) = {
    self ! SnapshotOffer(SnapshotMetadata(persistenceId, snap.seqNr), snap.snapshot)
  }

  protected def handleRecover: Receive = {
    val handleOfferAndThanSendCompleted: Receive = {
      case offer: SnapshotOffer =>
        receiveRecover(offer)
        self ! RecoveryCompleted
    }
    handleOfferAndThanSendCompleted orElse receiveRecover
  }

  whenUnhandled {
    case Event(event, _) if handleRecover.isDefinedAt(event) =>
      handleRecover(event)
      stay()
  }

  override def deleteSnapshots(criteria: SnapshotSelectionCriteria): Unit = {}
}

case class SnapshotWithSeqNr(snapshot: Any, seqNr: Long)