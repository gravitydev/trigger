Trigger
=======

A handful of scala utilities for working with Amazon SQS and SNS. Mostly based on Akka actors and finite state machines (FSM). Fully asynchronous.

Installation
------------
Add this to build.sbt:
```sbt
resolvers += "gravity" at "https://devstack.io/repo/gravitydev/public"

libraryDependencies += "com.gravitydev" %% "trigger" % "0.0.8-SNAPSHOT"
```

SqsQueueListener
----------------
Actor that listens (long-polling) on an Amazon SQS queue and applies the provided callback to any message received:

```scala
import com.gravitydev.trigger.SqsQueueListener
import akka.actor._
...

val sqsClient = new AmazonSQSClientAsync(...)

val sqsListener = system.actorOf(
  Props(
    classOf[SqsQueueListener], 
    sqsClient, 
    queueUrl, 
    message => println(message) // callback, provide your own
  )
)
```

Topic Queue
-----------
TODO
