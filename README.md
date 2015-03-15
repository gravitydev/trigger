Trigger
=======

A handful of scala utilities for working with Amazon SQS and SNS. Mostly based on Akka actors and finite state machines (FSM). Fully asynchronous.

Installation
------------
Add this to build.sbt:
```sbt
resolvers += "gravity" at "https://devstack.io/repo/gravitydev/public"

libraryDependencies += "com.gravitydev" %% "trigger" % "0.1.1-SNAPSHOT"
```

SqsQueueListener
----------------
Actor that listens (long-polling) on an Amazon SQS queue and applies the provided callback to any messages received:

```scala
import com.gravitydev.trigger.SqsQueueListener
import akka.actor._
...

val sqsClient = new AmazonSQSClientAsync(...)

val sqsListener = system.actorOf(
  Props(
    classOf[SqsQueueListener], 
    sqsClient, 
    queueUrl,                   // url of the queue (String)
    messages => println(messages) // callback, provide your own
  )
)
```

Topic Queue
-----------
TODO
