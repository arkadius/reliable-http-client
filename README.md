# reliable-http-client

*Reliable Http Client* uses *RabbitMQ* as a proxy for HTTP calls. It also provides persistent Akka FSM Actors (using *akka-persistence*) for recovery of subscriptions for responses.

## Idea

The idea is to create mechanism that proxing all http request-response communication thru [AMQP](https://en.wikipedia.org/wiki/Advanced_Message_Queuing_Protocol).

Consinder situation:

```scala
system.actorOf(Props(new Actor {
  def receive = {
    case DoJob =>
      httpClient.send(request) pipeTo self
    case Response =>
      // handle respone
  }
}))
```

When given actor will be shutdowned e.g. because of a system failure, the response message will never been delivered.

Thanks to *reliable-http-client* the same execution cause that actor after restart will got the response message.

## Examples

```scala
val rhttpc = ReliableHttp()

system.actorOf(Props(new Actor {
  def receive = {
    case DoJob =>
      val request = HttpRequest().withUri("http://ws-host:port").withMethod(HttpMethods.POST).withEntity(msg)
      rhttpc.send(request).toFuture.pipeTo self
    case Response =>
      // handle respone
  }
}))
```

The example above cause that request/response will be send thru *amqp* durable queues. If http service idle for a while and during this we need to restart our app, response message will be delivered to response *amqp* durable queue.
But after restart our application won't know what to do with response - in which state was sending actor. So we need to also persist state of our actor including acknowledged published requests.
It can be achived by *ReliableFSM* delivered by this project.

```scala
implicit val system = ActorSystem()
val rhttpc = ReliableHttp()

system.actorOf(Props(new FooBarActor(rhttpc)), "app-foobar")

class FooBarActor(rhttpc: ReliableHttp) extends ReliableFSM[FooBarState, FooBarData] {
  import context.dispatcher
  
  override protected val id = "foobar"
  override protected val persistenceCategory = "app"
  override protected val subscriptionManager = rhttpc.subscriptionManager

  startWith(InitState, EmptyData)

  when(InitState) {
    case Event(SendMsg(msg), _) =>
      val request = HttpRequest().withUri("http://ws-host:port").withMethod(HttpMethods.POST).withEntity(msg)
      rhttpc.send(request).pipeTo this
      goto(WaitingForResponseState) replyingAfterSave()
  }
  
  when(WaitingForResponseState) {
    case Event(httpResponse: HttpResponse, _) =>
      self forward httpResponse.entity.asInstanceOf[HttpEntity.Strict].data.utf8String
      stay()
    case Event("foo", _) => goto(FooState) acknowledgingAfterSave()
    case Event("bar", _) => goto(BarState) acknowledgingAfterSave()
  }

  when(FooState, stateTimeout = 5 minutes) {
    case Event(StateTimeout, _) => stop()
  }

  when(BarState, stateTimeout = 5 minutes) {
    case Event(StateTimeout, _) => stop()
  }

  whenUnhandled {
    case Event(CurrentState, _) =>
      sender() ! stateName
      stay()
  }
}
```

Slightly difference is that instead of `rhttpc.send(request).toFuture.pipeTo self` we are doing `rhttpc.send(request).pipeTo this`. Also our actor extends *ReliableFSM* which handles messages from queues and persist actor's state. If our application was shutdowned in *WaitingForResponseState*, after restart actor will recover their state and handle response. Full example you can check out [here](https://github.com/arkadius/reliable-http-client/blob/master/sample/sample-app/src/main/scala/rhttpc/sample/SampleApp.scala). There are also [*Docker* tests](https://github.com/arkadius/reliable-http-client/blob/master/sample/test/src/test/scala/rhttpc/test/DeliveryResponseAfterRestartWithDockerSpec.scala) that reproduce this situation. All you need to run them is installed *Docker*.

## Architecture

Big picture:
![Bit picture](https://raw.githubusercontent.com/arkadius/reliable-http-client/images/images/rhttpc-arch.png)

Request-response sequence:
![Request-response](https://raw.githubusercontent.com/arkadius/reliable-http-client/images/images/rhttpc-request-response.png)

## Status

WIP

# 3rd part libraries

*rhttpc* uses [op-rabbit](https://github.com/SpinGo/op-rabbit) for communication thru *AMQP*. It uses [akka-persistence](https://github.com/akka/akka) for storing of snapshots of FSM states.


# License

The reliable-http-client is released under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0).
