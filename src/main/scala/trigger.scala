package com.gravitydev.trigger

import play.api.libs.json.Json
import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.sns.AmazonSNSAsyncClient
import com.amazonaws.services.sqs.AmazonSQSAsyncClient
import com.amazonaws.services.sqs.model._
import com.amazonaws.services.sns.model.{Topic => _, _}
import akka.actor.ActorSystem
import scala.concurrent.Future
import scala.collection.JavaConverters._
import com.typesafe.scalalogging.slf4j.StrictLogging
import com.gravitydev.awsutil._

class Queue (val url: String, val arn: String)
class TopicQueue (url: String, arn: String, val subscriptionArn: String) extends Queue(url, arn)

/**
 * Provide basic push notifications by combining SNS with SQS long-polling.
 */
class TriggerClient (val arnPrefix: String, val sqs: AmazonSQSAsyncClient, val sns: AmazonSNSAsyncClient)(implicit system: ActorSystem) extends StrictLogging {
  import system.dispatcher
  
  def createTopic (name: String) = for {
    _ <- withAsyncHandler {(handler: AsyncHandler[CreateTopicRequest,CreateTopicResult]) => 
      sns.createTopicAsync(
        new CreateTopicRequest()
          .withName(name), 
        handler
      )
    }
  } yield name
    
  def createQueue (name: String): Future[Queue] = for {
    q <- withAsyncHandler[CreateQueueRequest, CreateQueueResult] {
      sqs.createQueueAsync (
        new CreateQueueRequest()
          .withQueueName(name)
          .withAttributes(Map(
            "ReceiveMessageWaitTimeSeconds" -> "20"
          ).asJava),
        _
      )
    }
    r <- withAsyncHandler[GetQueueAttributesRequest,GetQueueAttributesResult] {
      sqs.getQueueAttributesAsync(
        new GetQueueAttributesRequest().withAttributeNames("QueueArn").withQueueUrl(q.getQueueUrl),
        _
      )
    }
  } yield new Queue(q.getQueueUrl, r.getAttributes().get("QueueArn"))
  
  def deleteQueue (queue: Queue): Future[Unit] = withAsyncHandler[DeleteQueueRequest, Void] {
    sqs.deleteQueueAsync(
      new DeleteQueueRequest().withQueueUrl(queue.url),
      _
    ) 
  } map (_ => ())
  
  def createTopicQueue (queueName: String, topicName: String, createIfAbsent: Boolean = true): Future[TopicQueue] = {
    val res = for {
      queue <- createQueue(queueName)
      
      // set permissions on the queue
      _ <- withAsyncHandler[SetQueueAttributesRequest, Void] {
        sqs.setQueueAttributesAsync(
          new SetQueueAttributesRequest()
            .withQueueUrl(queue.url)
            .withAttributes(
               Map[String,String](
                 "Policy" -> allowTopicPolicy(queue.arn, arnPrefix + topicName)
               ).asJava
            ),
          _
        )
      }
      
      subscription <- withAsyncHandler[SubscribeRequest, SubscribeResult] {
        sns.subscribeAsync(
          new SubscribeRequest()
            .withEndpoint(queue.arn)
            .withProtocol("sqs")
            .withTopicArn(arnPrefix + topicName),
          _
        )
      }
    } yield new TopicQueue(queue.url, queue.arn, subscription.getSubscriptionArn)

    // if the topic is not there, try to create it
    res recoverWith { 
      case e: NotFoundException if createIfAbsent => for {
        create  <- createTopic(topicName) 
        subs    <- createTopicQueue(queueName, topicName, createIfAbsent = false) // if absent again, don't try to create, we already tried
      } yield subs
    }
  }
  
  def deleteTopicQueue (queue: TopicQueue) = for {
    unsubscribe <- List(sns.unsubscribe {
      new UnsubscribeRequest().withSubscriptionArn(queue.subscriptionArn)
    })
    delete <- List(sqs.deleteQueue {
      new DeleteQueueRequest().withQueueUrl(queue.url)
    })
  } yield ()
  
  def publish (topicName: String, subject: String, message: String): Future[String] = withAsyncHandler[PublishRequest,PublishResult] {
    logger.debug("Publishing [" + subject + ": " + message + "]")
    sns.publishAsync (
      new PublishRequest().withTopicArn(arnPrefix + topicName).withSubject(subject).withMessage(message),
      _
    )
  } map {_.getMessageId} recover { // TODO: fix weird recover here
    case e => logger.error("Failed to publish ["+subject+": "+message+"] to topic ["+topicName+"]", e); throw e
  }
  
  def receiveMessages (queue: Queue): Future[List[Message]] = {
    logger.debug("Checking messages in queue: " + queue.url)
    
    (for {
      received <- {
        logger.debug("Calling ReceiveMessageAsync")
        withAsyncHandler [ReceiveMessageRequest, ReceiveMessageResult] {
          sqs.receiveMessageAsync(
            new ReceiveMessageRequest()
              .withQueueUrl(queue.url)
              .withWaitTimeSeconds(20),
            _
          )
        } recover {case e => logger.error("Error receiving message", e); throw e}
      }
    } yield {
      logger.debug("Received: " + received)
      val messages = received.getMessages()
      if (messages.size > 0) {
        withAsyncHandler [DeleteMessageBatchRequest, DeleteMessageBatchResult] {
          sqs.deleteMessageBatchAsync(
            new DeleteMessageBatchRequest()
              .withEntries(
                received.getMessages().asScala.map {m =>
                  new DeleteMessageBatchRequestEntry()
                    .withReceiptHandle(m.getReceiptHandle())
                    .withId(m.getMessageId())
                }.asJava
              )
              .withQueueUrl(queue.url),
            _
          )
        } recover {case e => logger.error("Error deleting message", e)}
      }
      
      logger.debug("Message deleted")
      received.getMessages().asScala.toList
    })
  }
  
  private def allowTopicPolicy (queueArn: String, topicArn: String) = Json.stringify(
    Json.obj(
      "Statement" -> Json.arr(
        Json.obj(
          "Sid" -> "MySQSPolicy001",
          "Effect" -> "Allow",
          "Principal" -> Json.obj("AWS"->"*"),
          "Action" -> "sqs:SendMessage",
          "Resource" -> queueArn,
          "Condition" -> Json.obj(
            "ArnEquals" -> Json.obj("aws:SourceArn" -> topicArn)
          )
        )
      ) 
    )  
  )
}
