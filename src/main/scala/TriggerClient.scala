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
import com.typesafe.scalalogging.StrictLogging
import com.gravitydev.awsutil.awsToScala

class Queue (val arn: String, val url: String)
class Topic (val arn: String)
class TopicQueue (val topic: Topic, queue: Queue, val subscriptionArn: String) extends Queue(queue.arn, queue.url)

/**
 * Provide basic push notifications by combining SNS with SQS long-polling.
 */
class TriggerClient (val sqs: AmazonSQSAsyncClient, val sns: AmazonSNSAsyncClient)(implicit system: ActorSystem) extends StrictLogging {
  import system.dispatcher
  
  def createTopic (name: String): Future[Topic] = for {
    res <-awsToScala(sns.createTopicAsync)(
        new CreateTopicRequest()
          .withName(name)
      )
  } yield new Topic(res.getTopicArn)
    
  def createQueue (name: String): Future[Queue] = for {
    q <- awsToScala(sqs.createQueueAsync)(
      new CreateQueueRequest()
        .withQueueName(name)
        .withAttributes(Map(
          "ReceiveMessageWaitTimeSeconds" -> "20"
        ).asJava)
    )
    r <- awsToScala(sqs.getQueueAttributesAsync)(
      new GetQueueAttributesRequest().withAttributeNames("QueueArn").withQueueUrl(q.getQueueUrl)
    )
  } yield new Queue(r.getAttributes().get("QueueArn"), q.getQueueUrl)
  
  def deleteQueue (queue: Queue): Future[Unit] = awsToScala(sqs.deleteQueueAsync)(
    new DeleteQueueRequest().withQueueUrl(queue.url)
  ) map (_ => ())

  /*  
  def createTopicQueue (queueName: String, topicName: String, createIfAbsent: Boolean = true): Future[TopicQueue] = {
    val res = for {
      queue <- createQueue(queueName)
      
      // set permissions on the queue
      _ <- awsToScala(sqs.setQueueAttributesAsync)(
        new SetQueueAttributesRequest()
          .withQueueUrl(queue.url)
          .withAttributes(
             Map[String,String](
               "Policy" -> allowTopicPolicy(queue.arn, arnPrefix + topicName)
             ).asJava
          )
      )
      
      subscription <- awsToScala(sns.subscribeAsync)(
        new SubscribeRequest()
          .withEndpoint(queue.arn)
          .withProtocol("sqs")
          .withTopicArn(arnPrefix + topicName)
      )
    } yield new TopicQueue(topicName, queue.url, queue.arn, subscription.getSubscriptionArn)

    // if the topic is not there, try to create it
    res recoverWith { 
      case e: NotFoundException if createIfAbsent => for {
        create  <- createTopic(topicName) 
        subs    <- createTopicQueue(queueName, topicName, createIfAbsent = false) // if absent again, don't try to create, we already tried
      } yield subs
    }
  }
  */

  def createTopicQueue (topic: Topic, queueName: String): Future[TopicQueue] = for {
    queue <- createQueue(queueName)
    
    // set permissions on the queue
    _ <- awsToScala(sqs.setQueueAttributesAsync)(
      new SetQueueAttributesRequest()
        .withQueueUrl(queue.url)
        .withAttributes(
           Map[String,String](
             "Policy" -> allowTopicPolicy(queue.arn, topic.arn)
           ).asJava
        )
    )
    
    subscription <- awsToScala(sns.subscribeAsync)(
      new SubscribeRequest()
        .withEndpoint(queue.arn)
        .withProtocol("sqs")
        .withTopicArn(topic.arn)
    )
  } yield new TopicQueue(topic, queue, subscription.getSubscriptionArn)
  
  def deleteTopicQueue (queue: TopicQueue) = for {
    _ <- awsToScala(sns.unsubscribeAsync) {
      new UnsubscribeRequest().withSubscriptionArn(queue.subscriptionArn)
    }
    _ <- awsToScala(sqs.deleteQueueAsync) {
      new DeleteQueueRequest().withQueueUrl(queue.url)
    }
  } yield ()
  
  def publish (topic: Topic, subject: String, message: String): Future[String] = awsToScala(sns.publishAsync)(
    new PublishRequest().withTopicArn(topic.arn).withSubject(subject).withMessage(message)
  ) map {_.getMessageId} recover { // TODO: fix weird recover here
    case e => logger.error("Failed to publish ["+subject+": "+message+"] to topic ["+topic.arn+"]", e); throw e
  }
  
  def receiveMessages (queue: Queue): Future[List[Message]] = {
    logger.debug("Checking messages in queue: " + queue.url)
    
    (for {
      received <- {
        logger.debug("Calling ReceiveMessageAsync")
        awsToScala(sqs.receiveMessageAsync)(
          new ReceiveMessageRequest()
            .withQueueUrl(queue.url)
            .withWaitTimeSeconds(20)
        ) recover {case e => logger.error("Error receiving message", e); throw e}
      }
    } yield {
      logger.debug("Received: " + received)
      val messages = received.getMessages()
      if (messages.size > 0) {
        awsToScala(sqs.deleteMessageBatchAsync)(
          new DeleteMessageBatchRequest()
            .withEntries(
              received.getMessages().asScala.map {m =>
                new DeleteMessageBatchRequestEntry()
                  .withReceiptHandle(m.getReceiptHandle())
                  .withId(m.getMessageId())
              }.asJava
            )
            .withQueueUrl(queue.url)
        ) recover {case e => logger.error("Error deleting message", e)}
      }
      
      logger.debug("Message deleted")
      received.getMessages().asScala.toList
    })
  }
  
  private def allowTopicPolicy (queueArn: String, topicArn: String) = Json.stringify(
    Json.obj(
      "Statement" -> Json.arr(
        Json.obj(
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

