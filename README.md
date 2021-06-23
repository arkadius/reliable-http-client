# reliable-http-client

[![circle-ci](https://circleci.com/gh/arkadius/reliable-http-client/tree/master.svg?style=shield&circle-token=1287932dad2962d954d6eac289d36cb4f5a05e2b)](https://circleci.com/gh/arkadius/reliable-http-client/tree/master)
[![Join the chat at https://gitter.im/arkadius/reliable-http-client](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/arkadius/reliable-http-client?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

*Reliable Http Client* is a set of tools making HTTP communication more reliable. It supports: *at least one delivery guarantee* as well as *retry strategies* including *durable exponential backoff* and *dead letter queue*.

It provides an abstraction layer over publisher/subscriber transport. Currently [AMQP](https://en.wikipedia.org/wiki/Advanced_Message_Queuing_Protocol) transport and [Json4s](https://github.com/json4s/json4s) serialization modules are implemented.

It can be used with any HTTP client. It includes a wrapper for *Akka HTTP* in a separate module, but it can be used with your own. In fact, it can be used even with any request/response client - not necessarily HTTP.
    
There are 3 basic usage scenarios:
- in-only: when responses are irrelevant -> You only care about requests being delivered. 
- in-out: when You are interested in response but your response consumer is stateless
- in-out with subscriptions for responses: when You are interested in responses and your response consumer is stateful

For the third scenario there exists a provided module with *persistent Akka FSM Actors* (using *akka-persistence*) for easy recovery of subscriptions for responses.

## AMQP transport

If You only want to use *Akka* wrapper for *amqp-client* with *Json4s* serialization
```sbt
libraryDependencies += "org.rhttpc" %% "rhttpc-amqp" % "0.9.0"
libraryDependencies += "org.rhttpc" %% "rhttpc-json4s" % "0.9.0"
```

Then:
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

For clients with *AMQP* transport and *Json4s* serialization
```sbt
libraryDependencies += "org.rhttpc" %% "rhttpc-amqp" % "0.9.0"
libraryDependencies += "org.rhttpc" %% "rhttpc-json4s" % "0.9.0"
libraryDependencies += "org.rhttpc" %% "rhttpc-client" % "0.9.0"
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

Consider the following situation:

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

When given actor is shutdown e.g. because of a system failure, the response message will never been delivered.

Thanks to *rhttpc* the same execution guarantees that after restart actor will receive the response message.

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

The example above causes requests/responses to be sent through *AMQP* durable queues. If http service is idle for a while and You need to restart your application - response messages will be delivered to response *AMQP* durable queue.
But after restart your application won't know what to do with the response - in what state the sending actor was. So You also need to  persist the state of your actor which includes acknowledged published requests.
It can be achieved with *ReliableFSM* delivered by this project.

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

A slight difference is that instead of `rhttpc.send(request).toFuture pipeTo self` we are doing `rhttpc.send(request) pipeTo this`. Also our actor extends *ReliableFSM* which handles messages from queues and persists actor's state. If our application was shutdown in *WaitingForResponseState*, after restart actor will recover their state and handle responses. You can check out the full example [here](https://github.com/arkadius/reliable-http-client/blob/master/sample/sample-app/src/main/scala/rhttpc/sample/SampleApp.scala). There are also [*Docker* tests](https://github.com/arkadius/reliable-http-client/blob/master/sample/test/src/test/scala/rhttpc/test/DeliveryResponseAfterRestartWithDockerSpec.scala) that reproduce this situation. All you need to run them is installed *Docker*. If you have one, just run `sbt testProj/test 2>&1 | tee test.log`

## Architecture

### Big picture
![Bit picture](https://raw.githubusercontent.com/arkadius/reliable-http-client/images/images/rhttpc-arch2.png)

Proxy can also be run as a separate process.

### Request-response sequence

![Request-response](https://raw.githubusercontent.com/arkadius/reliable-http-client/images/images/rhttpc-request-response.png)

# 3rd part libraries

*rhttpc* uses
- [rabbitmq-client](https://github.com/rabbitmq/rabbitmq-java-client) for communication through *AMQP*
- [json4s](https://github.com/json4s/json4s) for serialization
- [argonaut](https://github.com/argonaut-io/argonaut) for serialization
- [akka-http](https://github.com/akka/akka) for HTTP communication
- [akka-persistence](https://github.com/akka/akka) for storing snapshots of FSM states

# License

The *reliable-http-client* is released under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0).
