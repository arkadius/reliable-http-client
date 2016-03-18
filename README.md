# reliable-http-client

[![circle-ci](https://circleci.com/gh/arkadius/reliable-http-client/tree/master.svg?style=shield&circle-token=1287932dad2962d954d6eac289d36cb4f5a05e2b)](https://circleci.com/gh/arkadius/reliable-http-client/tree/master)
[![Codacy Badge](https://api.codacy.com/project/badge/grade/46b882f05c87468a849b8805fb9aeb68)](https://www.codacy.com/app/arek-burdach/reliable-http-client)
[![Stories in Ready](https://badge.waffle.io/arkadius/reliable-http-client.svg?label=ready&title=Ready)](http://waffle.io/arkadius/reliable-http-client)
[![Join the chat at https://gitter.im/arkadius/reliable-http-client](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/arkadius/reliable-http-client?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

*Reliable Http Client* is a set of tools making HTTP communication more reliable. It supports: *at least one delivery guaranty* and *retry strategies* including *durable exponential backoff* and *dead letter queue*.

It abstracting from publisher/subscriber transport. Currently there are implemented [AMQP](https://en.wikipedia.org/wiki/Advanced_Message_Queuing_Protocol) transport and [Json4s](https://github.com/json4s/json4s) serialization modules.

It can be used with any HTTP client. It includes wrapper for *Akka HTTP* in separate module, but You can use it with your own. In fact, it can be used with any request/response client, not necessarily HTTP.
    
There are 3 basic usage scenarios:
- in-only: when You are not interested in response, You only want to make sure that request was delivered  
- in-out: when You are interested in response but your response consumer is stateless
- in-out with subscriptions for response: when You are interested in response and your response consumer is stateful

For the third scenario there is also provided module with *persistent Akka FSM Actors* (using *akka-persistence*) for easy recovery of subscriptions for responses.

## AMQP transport

If You only want to use *Akka* wrapper for *amqp-client* with *Json4s* serialization
```sbt
libraryDependencies += "org.rhttpc" %% "rhttpc-amqp" % "0.5.3"
libraryDependencies += "org.rhttpc" %% "rhttpc-json4s" % "0.5.3"
```

Than:
```scala
import akka.actor._
import rhttpc.transport.amqp._
import rhttpc.transport.json4s._
import rhttpc.transport.json4s.CommonFormats._

implicit val actorSystem = ActorSystem()
import actorSystem.dispatcher

AmqpConnectionFactory.connect(actorSystem).map { connection =>
  val transport = AmqpTransport(connection)
  
  val publisher = transport.publisher[String]("foo-queue")
  publisher.publish("foo-message")
  
  val subscriber = transport.subscriber[Int]("bar-queue", actorSystem.actorOf(Props(new Actor {
    def receive: Receive = {
      case i: Int => println(s"got: $i")
    }
  })))
  subscriber.run()
}
```

## Client

For using of client with *AMQP* transport and *Json4s* serialization
```sbt
libraryDependencies += "org.rhttpc" %% "rhttpc-amqp" % "0.5.3"
libraryDependencies += "org.rhttpc" %% "rhttpc-json4s" % "0.5.3"
libraryDependencies += "org.rhttpc" %% "rhttpc-client" % "0.5.3"
```

### In-only scenario

```scala
import akka.actor._
import rhttpc.transport.amqp._
import rhttpc.transport.json4s._
import rhttpc.transport.json4s.CommonFormats._
import rhttpc.client._

implicit val actorSystem = ActorSystem()
import actorSystem.dispatcher
AmqpConnectionFactory.connect(actorSystem).map { implicit connection =>
  val client = ReliableClientFactory().inOnly[String](ownClient.send)
  client.send("foo")
}
```

### In-out with stateless consumer

```scala
import akka.actor._
import rhttpc.transport.amqp._
import rhttpc.transport.json4s._
import rhttpc.transport.json4s.CommonFormats._
import rhttpc.client._

implicit val actorSystem = ActorSystem()
import actorSystem.dispatcher
AmqpConnectionFactory.connect(actorSystem).map { implicit connection =>
  val client = ReliableClientFactory().inOut[String, String](
    send = ownClient.send,
    handleResponse = consumer.consume
  )
  client.send("foo")
}
```

### In-out with stateful consumer

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

Thanks to *rhttpc* the same execution cause that actor after restart will got the response message.

```scala
val rhttpc = ReliableHttpClientFactory().inOutWithSubscriptions(amqpConnection)

system.actorOf(Props(new Actor {
  def receive = {
    case DoJob =>
      val request = HttpRequest().withUri("http://ws-host:port").withMethod(HttpMethods.POST).withEntity(msg)
      rhttpc.send(request).toFuture pipeTo self
    case Response =>
      // handle respone
  }
}))
```

The example above cause that request/response will be send through *AMQP* durable queues. If http service idle for a while and during this we need to restart our app, response message will be delivered to response *AMQP* durable queue.
But after restart our application won't know what to do with response - in which state was sending actor. So we need to also persist state of our actor including acknowledged published requests.
It can be achived by *ReliableFSM* delivered by this project.

```scala
val rhttpc = ReliableHttpClientFactory().inOutWithSubscriptions(amqpConnection)

system.actorOf(Props(new FooBarActor(rhttpc)), "app-foobar")

class FooBarActor(rhttpc: InOutReliableHttpClient) extends ReliableFSM[FooBarState, FooBarData] {
  import context.dispatcher
  
  override protected val id = "foobar"
  override protected val persistenceCategory = "app"
  override protected val subscriptionManager = rhttpc.subscriptionManager

  startWith(InitState, EmptyData)

  when(InitState) {
    case Event(SendMsg(msg), _) =>
      val request = HttpRequest().withUri("http://ws-host:port").withMethod(HttpMethods.POST).withEntity(msg)
      rhttpc.send(request) pipeTo this
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

Slightly difference is that instead of `rhttpc.send(request).toFuture pipeTo self` we are doing `rhttpc.send(request) pipeTo this`. Also our actor extends *ReliableFSM* which handles messages from queues and persist actor's state. If our application was shutdowned in *WaitingForResponseState*, after restart actor will recover their state and handle response. Full example you can check out [here](https://github.com/arkadius/reliable-http-client/blob/master/sample/sample-app/src/main/scala/rhttpc/sample/SampleApp.scala). There are also [*Docker* tests](https://github.com/arkadius/reliable-http-client/blob/master/sample/test/src/test/scala/rhttpc/test/DeliveryResponseAfterRestartWithDockerSpec.scala) that reproduce this situation. All you need to run them is installed *Docker*. If you have one, just run `sbt testProj/test 2>&1 | tee test.log`

## Architecture

### Big picture
![Bit picture](https://raw.githubusercontent.com/arkadius/reliable-http-client/images/images/rhttpc-arch2.png)

Proxy can be also run as separate process.

### Request-response sequence

![Request-response](https://raw.githubusercontent.com/arkadius/reliable-http-client/images/images/rhttpc-request-response.png)

# 3rd part libraries

*rhttpc* uses
- [rabbitmq-client](https://github.com/rabbitmq/rabbitmq-java-client) for communication through *AMQP*
- [json4s](https://github.com/json4s/json4s) for serialization
- [akka-http](https://github.com/akka/akka) for HTTP communication
- [akka-persistence](https://github.com/akka/akka) for storing snapshots of FSM states

# License

The *reliable-http-client* is released under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0).
