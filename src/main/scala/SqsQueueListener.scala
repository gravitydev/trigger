package com.gravitydev.trigger

import akka.actor._
import com.amazonaws.services.sqs.AmazonSQSAsyncClient
import com.amazonaws.services.sqs.model._
import com.gravitydev.awsutil.withAsyncHandler
import scala.collection.JavaConverters._

object SqsQueueListener {
  sealed trait State
  private case object Waiting extends State
  private case object Processing extends State
  
  private sealed trait Command
  private case object Query extends Command
  private case class Process(messages: List[Message]) extends Command
}

class SqsQueueListener (
  sqs: AmazonSQSAsyncClient, 
  queueUrl: String, 
  processor: Message => Unit
) extends FSM[SqsQueueListener.State, List[Message]] with ActorLogging {
  import context.dispatcher
  import SqsQueueListener._
  
  override def preStart () = {
    log.info("Started SqsQueueListener for " + queueUrl + " and processor: " + processor)
    self ! Query
  }
  
  startWith(Processing, Nil)
  
  when(Processing) {
    case Event(Query, _) => goto(Waiting) using Nil
  }
  
  when(Waiting) {
    case Event(Process(messages), _) => goto(Processing) using messages
  }
  
  onTransition {
    case _ -> Waiting => withAsyncHandler[ReceiveMessageRequest, ReceiveMessageResult] {
      log.debug("Querying SQS")
      sqs.receiveMessageAsync(
        new ReceiveMessageRequest()
          .withMaxNumberOfMessages(10)
          .withWaitTimeSeconds(20)
          .withQueueUrl(queueUrl),
        _
      )
    } map {res => 
      log.debug("Response from SQS: " + res)
      
      val messages = res.getMessages().asScala.toList
      
      log.debug("Received messages: " + messages.size)
      
      if (messages.nonEmpty) {
        withAsyncHandler [DeleteMessageBatchRequest, DeleteMessageBatchResult] {
          sqs.deleteMessageBatchAsync(
            new DeleteMessageBatchRequest()
              .withEntries(
                messages.map {m =>
                  new DeleteMessageBatchRequestEntry()
                    .withReceiptHandle(m.getReceiptHandle())
                    .withId(m.getMessageId())
                }.asJava
              )
              .withQueueUrl(queueUrl),
            _
          )
        }
      }
      self ! Process(messages)
    }
    case _ -> Processing => {
      log.debug("Processing: " + stateData)
      nextStateData foreach {processor ! _}
      self ! Query
    }
  }
  
  initialize()
}
