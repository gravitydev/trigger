package com.gravitydev.trigger

import akka.actor._
import com.amazonaws.services.sqs.AmazonSQSAsyncClient
import com.amazonaws.services.sqs.model._
import com.gravitydev.awsutil.awsToScala
import scala.collection.JavaConverters._

object SqsQueueListener {
  sealed trait State
  private case object Waiting extends State
  private case object Processing extends State
  
  private sealed trait Command
  private case object Query extends Command
  private case class Process(messages: List[Message]) extends Command
}

/**
 * Actor that long-polls on an Amazon SQS queue and calls a callback whenever 
 * it receives one or more sqs messages
 */
class SqsQueueListener (
  sqs: AmazonSQSAsyncClient, 
  queueUrl: String, 
  callback: List[Message] => Unit,
  messageAttributes: Set[String] = Set("*")
) extends FSM[SqsQueueListener.State, List[Message]] with ActorLogging {
  import context.dispatcher
  import SqsQueueListener._
  
  override def preStart () = {
    super.preStart()
    log.info("Started SqsQueueListener for " + queueUrl)
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
    case _ -> Waiting => awsToScala(sqs.receiveMessageAsync)(
      new ReceiveMessageRequest()
        .withMaxNumberOfMessages(10)
        .withWaitTimeSeconds(20)
        .withQueueUrl(queueUrl)
        .withMessageAttributeNames(messageAttributes.asJava)
    ) map {res => 
      log.debug("Response from SQS: " + res)
      
      val messages = res.getMessages().asScala.toList
      
      log.debug("Received messages: " + messages.size)
      
      if (messages.nonEmpty) {
        awsToScala(sqs.deleteMessageBatchAsync)(
          new DeleteMessageBatchRequest()
            .withEntries(
              messages.map {m =>
                new DeleteMessageBatchRequestEntry()
                  .withReceiptHandle(m.getReceiptHandle())
                  .withId(m.getMessageId())
              }.asJava
            )
            .withQueueUrl(queueUrl)
        )
      }
      self ! Process(messages)
    }
    case _ -> Processing => {
      log.debug("Processing: " + stateData)
      if (nextStateData.nonEmpty) callback(nextStateData)
      self ! Query
    }
  }
  
  initialize()
}

