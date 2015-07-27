package reliablehttpc.test

import dispatch.{Future => DispatchFuture, _}
import dispatch.Defaults._

import scala.concurrent._

class FooBarClient(baseUrl: Req, id: String) {
  implicit val successPredicate = new retry.Success[Unit.type](_ => true)

  def foo(implicit ec: ExecutionContext): Future[Unit.type] =
    retry.Backoff() { () =>
      Http(baseUrl / id << "foo").map(_ => Unit)
    }
}
