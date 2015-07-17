# reliable-http-client

Reliable Http Client with API similar to [Dispatch](http://dispatch.databinder.net/Dispatch.html) using [RabbitMQ](https://www.rabbitmq.com)

## Idea

The idea is to create mechanism that is adapter for Dispatch client which will execute request-response http communication thru [AMQP](https://en.wikipedia.org/wiki/Advanced_Message_Queuing_Protocol).

Consinder situation:

```scala
system.actorOf(Props(new Actor {
  def receive = {
    case DoJob =>
      Http(url << request > as.Response) pipeTo self
    case Response =>
      // handle respone
  }
}))
```

When given actor will be shutdowned e.g. because of a system failure, the response message will never been delivered.

Thanks to reliable-http-client the same execution cause that actor after restart will got the response message.

For actor persistence will be used [Akka persistence](http://doc.akka.io/docs/akka/snapshot/scala/persistence.html)

## Status

WIP
