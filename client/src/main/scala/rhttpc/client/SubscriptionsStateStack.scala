package rhttpc.client

case class SubscriptionsStateStack private (stack: List[SubscriptionsState]) {
  import SubscriptionsStateStack._

  def withNextState(onAllRequestsPublished: Set[SubscriptionOnResponse] => Unit) = copy(stack = SubscriptionsState(onAllRequestsPublished) :: stack)

  def withRegisteredPromise(sub: SubscriptionOnResponse): SubscriptionsStateStack =
    withHead(_.withRegisteredPromise(sub))

  def withPublishedRequestFor(sub: SubscriptionOnResponse): SubscriptionsStateStack =
    withCompletedSubscription(sub)(_.withPublishedRequestFor(sub))

  def withAbortedRequestFor(sub: SubscriptionOnResponse): SubscriptionsStateStack =
    withCompletedSubscription(sub)(_.withAbortedRequestFor(sub))

  def withConsumedSubscription(sub: SubscriptionOnResponse): SubscriptionsStateStack =
    copy(stack = consumeSubscriptionOnStack(stack)(sub, _.withConsumedSubscription(sub)))
  
  private def withHead(transform: SubscriptionsState => SubscriptionsState): SubscriptionsStateStack = stack match {
    case head :: tail => copy(stack = transform(head) :: tail)
    case Nil          => copy(stack = transform(SubscriptionsState(_ => Unit)) :: stack)
  }

  private def withCompletedSubscription(sub: SubscriptionOnResponse)
                                       (complete: SubscriptionsState => SubscriptionsState): SubscriptionsStateStack = {
    copy(stack = completeSubscriptionOnStack(stack)(sub, complete))
  }
  
}

object SubscriptionsStateStack {
  def apply(): SubscriptionsStateStack = SubscriptionsStateStack(List.empty)
  
  private def completeSubscriptionOnStack(s: List[SubscriptionsState])
                                         (sub: SubscriptionOnResponse,
                                          complete: SubscriptionsState => SubscriptionsState): List[SubscriptionsState] = s match {
    case head :: tail if head.containsSubscription(sub) => complete(head) :: tail
    case head :: tail => head :: completeSubscriptionOnStack(tail)(sub, complete)
    case Nil => Nil
  }

  private def consumeSubscriptionOnStack(s: List[SubscriptionsState])
                                        (sub: SubscriptionOnResponse,
                                         consume: SubscriptionsState => SubscriptionConsumptionResult): List[SubscriptionsState] = s match {
    case head :: tail if head.containsSubscription(sub) =>
      consume(head) match {
        case AllSubscriptionsConsumed =>
          tail
        case SomeSubscriptionsLeft(updated) =>
          updated :: tail
      }
    case head :: tail => head :: consumeSubscriptionOnStack(tail)(sub, consume)
    case Nil => Nil
  }
}

case class SubscriptionsState private (private val subscriptions: Map[SubscriptionOnResponse, SubscriptionState])
                                      (onAllRequestsPublished: Set[SubscriptionOnResponse] => Unit) {
  def containsSubscription(sub: SubscriptionOnResponse) = subscriptions.contains(sub)

  def withRegisteredPromise(sub: SubscriptionOnResponse): SubscriptionsState = copy(subscriptions = subscriptions + (sub -> SubscriptionPromisedState))(onAllRequestsPublished)

  def withPublishedRequestFor(sub: SubscriptionOnResponse): SubscriptionsState =
    copy(subscriptions = subscriptions.updated(sub, RequestPublishedState))(onAllRequestsPublished).completionResult

  def withAbortedRequestFor(sub: SubscriptionOnResponse): SubscriptionsState =
    copy(subscriptions = subscriptions - sub)(onAllRequestsPublished).completionResult
  
  private def completionResult: SubscriptionsState = {
    val published = subscriptions.filter {
      case (k, v) => v.isPublished
    }
    if (published.size == subscriptions.size) {
      onAllRequestsPublished(published.keys.toSet)
    }
    this
  }

  def withConsumedSubscription(sub: SubscriptionOnResponse): SubscriptionConsumptionResult =
    copy(subscriptions = subscriptions.updated(sub, SubscriptionConsumedState))(onAllRequestsPublished).consumptionResult
  
  private def consumptionResult: SubscriptionConsumptionResult = {
    if (subscriptions.values.forall(_ == SubscriptionConsumedState)) {
      AllSubscriptionsConsumed
    } else {
      SomeSubscriptionsLeft(this)
    }
  }
}

sealed trait SubscriptionState {
  def isPublished: Boolean
}

case object SubscriptionPromisedState extends SubscriptionState {
  override def isPublished: Boolean = false
}
case object RequestPublishedState extends SubscriptionState {
  override def isPublished: Boolean = true
}
case object SubscriptionConsumedState extends SubscriptionState {
  override def isPublished: Boolean = true
}

sealed trait SubscriptionConsumptionResult

case object AllSubscriptionsConsumed extends SubscriptionConsumptionResult

case class SomeSubscriptionsLeft(updated: SubscriptionsState) extends SubscriptionConsumptionResult

object SubscriptionsState {
  def apply(onAllRequestsPublished: Set[SubscriptionOnResponse] => Unit): SubscriptionsState =
    new SubscriptionsState(Map.empty)(onAllRequestsPublished)
}