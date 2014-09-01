Trigger
=======

A handful of scala utilities for working with Amazon SQS and SNS. Mostly based on Akka actors and finite state machines (FSM). Fully asynchronous.

SqsQueueListener
----------------
Actor that listens (long-polling) on an Amazon SQS queue and applies the provided callback to any message received:

```scala
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
