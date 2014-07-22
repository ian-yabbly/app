package com.studio6.app.common.wq

import com.studio6.app.common.Log
import com.studio6.app.common.Predef._
import com.studio6.app.common.SecurityUtils
import com.studio6.app.common.proto.CommonProtos.WorkQueue.{Delayed => WQDelayed}
import com.studio6.app.common.proto.CommonProtos.WorkQueue.{Item => WQItem}
import com.studio6.app.common.redis.RedisClient
import com.studio6.app.common.redis.RedisClientHelper

import com.google.protobuf.ByteString
import com.google.protobuf.GeneratedMessage

import org.joda.time.DateTime

import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.exceptions.JedisConnectionException

import java.util.{List => JList}
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext

object predef {
  implicit class QString(name: String) {
    def idseq = "__wq.%s.idseq".format(name).toUtf8Bytes
    def items = "__wq.%s.items".format(name).toUtf8Bytes
    def pending = "__wq.%s.pending".format(name).toUtf8Bytes
    def inProgress = "__wq.%s.in-progress".format(name).toUtf8Bytes
    def fails = "__wq.%s.fails".format(name).toUtf8Bytes
  }
}

import predef._

object Item {
  case class Attempt(creationDate: DateTime, message: Option[String])
}

case class Item(id: String, creationDate: DateTime, item: Array[Byte], attempts: Seq[Item.Attempt])

case class Delayed(id: String, submitDate: DateTime, qname: String)

trait WorkQueueClient {
  def submit(qname: String, bytes: Array[Byte]): Unit
  def submit(qname: String, bytes: Array[Byte], delayMs: Int): Unit
  def retry(qname: String, item: Item): Unit
  def delete(qname: String): Unit
  def delete(qname: String, item: Item): Boolean
  def size(qname: String): Long
  def inProgressSize(qname: String): Long
  def failSize(qname: String): Long
  def getItems(qname: String, offset: Int, limit: Int): Seq[Item]
  def getInProgressItems(qname: String, offset: Int, limit: Int): Seq[Item]
  def getFailedItems(qname: String, offset: Int, limit: Int): Seq[Item]

  def allDelayedItems(): Seq[Delayed]
  def undelay(id: String): Boolean
  def undelayAllReadyToSubmit(): Unit
  def allQueueNames(): Seq[String]

  def start(): Unit
  def stop(): Unit
}

class RedisWorkQueueClient(val redisClient: RedisClient)
  extends WorkQueueClient
  with RedisClientHelper
{
  private val DelayItems = "__queue.delay-items".toUtf8Bytes
  private val DelayItemsSeq = "__queue.delay-idseq".toUtf8Bytes

  private val delayedExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
    override def newThread(r: Runnable) = new Thread(r, "delayed-item-worker")
  })

  override def start(): Unit = ()

  override def stop(): Unit = delayedExecutor.shutdownNow()

  override def submit(qname: String, bytes: Array[Byte]) = {
    val b = WQItem.newBuilder
    b.setCreationDate(DateTime.now().toString)
        .setItem(ByteString.copyFrom(bytes))

    withJedis { jedis => {
      val id = jedis.incr(qname.idseq).toString
      b.setId(id.toString)
      jedis.hset(qname.items, id.toUtf8Bytes, b.build.toByteArray)
      jedis.rpush(qname.pending, id.toUtf8Bytes)
    }}
  }

  override def submit(qname: String, bytes: Array[Byte], delayMs: Int) = {
    val submitTime = DateTime.now().plusMillis(delayMs)
    val b = WQDelayed.newBuilder
        .setDatetimeToSubmit(submitTime.toString)
        .setItem(ByteString.copyFrom(bytes))
        .setQname(qname)
    val id = withJedis { jedis => {
      val id = jedis.incr(DelayItemsSeq).toString
      b.setId(id.toString)
      jedis.hset(DelayItems, id.toUtf8Bytes, b.build.toByteArray)
      id
    }}

    // Schedule this work item
    val dms = submitTime.getMillis - System.currentTimeMillis
    delayedExecutor.schedule(
        new Runnable { override def run(): Unit = undelay(id) },
        dms, MILLISECONDS)
  }

  override def retry(qname: String, item: Item) = {
    val i = item.asInstanceOf[WQItem]
    val id = i.getId.toUtf8Bytes
    withJedis { jedis => {
      jedis.lrem(qname.fails, 0, id)
      jedis.rpush(qname.pending, id)
    }}
  }

  override def delete(qname: String) = {
    withJedis { jedis => {
      jedis.del(qname.items)
      jedis.del(qname.inProgress)
      jedis.del(qname.idseq)
      jedis.del(qname.fails)
      jedis.del(qname.pending)
    }}
  }

  override def delete(qname: String, item: Item) = {
    val i = item.asInstanceOf[WQItem]
    val id = i.getId.toUtf8Bytes
    withJedis { jedis => {
      jedis.lrem(qname.inProgress, 0, id)
      jedis.lrem(qname.fails, 0, id)
      jedis.lrem(qname.pending, 0, id)
      jedis.hdel(qname.items, id) == 1
    }}
  }

  override def size(qname: String) = {
    withJedis { jedis => {
      jedis.llen(qname.pending)
    }}
  }

  override def inProgressSize(qname: String) = {
    withJedis { jedis => {
      jedis.llen(qname.inProgress)
    }}
  }

  override def failSize(qname: String) = {
    withJedis { jedis => {
      jedis.llen(qname.fails)
    }}
  }

  override def getItems(qname: String, offset: Int, limit: Int) = getItems(qname, qname.pending, offset, limit)
  override def getInProgressItems(qname: String, offset: Int, limit: Int) = getItems(qname, qname.inProgress, offset, limit)
  override def getFailedItems(qname: String, offset: Int, limit: Int) = getItems(qname, qname.fails, offset, limit)

  override def allQueueNames() = {
    withJedis { jedis => {
      jedis.keys("__wq.*.idseq").map(n => n.substring(5, n.length - 6)).toSeq
    }}
  }

  override def allDelayedItems() = {
    withJedis { jedis => {
      allDelayedItems(jedis)
    }}
  }

  private def allRawDelayedItems(jedis: Jedis): Seq[WQDelayed] = {
    jedis.hkeys(DelayItems).map(id => {
      WQDelayed.parseFrom(jedis.hget(DelayItems, id))
    }).toSeq
  }

  private def allDelayedItems(jedis: Jedis) = {
    allRawDelayedItems(jedis).map(d => {
      Delayed(d.getId, DateTime.parse(d.getDatetimeToSubmit), d.getQname)
    }).toSeq
  }

  override def undelay(id: String) = {
    withJedis(jedis => inLock(jedis, "__queue.delay-lock", 1000l) {
      undelay(jedis, id)
    })
  }

  private def undelay(jedis: Jedis, id: String) = {
    jedis.hget(DelayItems, id.toUtf8Bytes) match {
      case null => false
      case bytes: Array[Byte] => {
        val delayed = WQDelayed.parseFrom(bytes)
        jedis.hdel(DelayItems, id.toUtf8Bytes)
        submit(delayed.getQname, delayed.getItem.toByteArray)
        true
      }
    }
  }

  override def undelayAllReadyToSubmit() = {
    withJedis(jedis => inLock(jedis, "__queue.delay-lock", 4000l) {
      allRawDelayedItems(jedis).foreach(d => {
        val now = DateTime.now
        val timeToSubmit = DateTime.parse(d.getDatetimeToSubmit)
        if (now.isAfter(timeToSubmit)) {
          undelay(jedis, d.getId)
        }
      })
    })
  }

  private def getItems(qname: String, name: Array[Byte], offset: Int, limit: Int): Seq[Item] = {
    withJedis(jedis => {
      val ids = jedis.lrange(name, offset, (limit-1))
      if (ids != null) {
        ids.map(id => toItem(WQItem.parseFrom(jedis.hget(qname.items, id))))
      } else {
        Nil
      }
    })
  }

  private def toItem(wi: WQItem): Item = {
    val attempts = wi.getAttemptList.map(a => {
      val message = if (a.hasMessage) Some(a.getMessage) else None
      Item.Attempt(DateTime.parse(wi.getCreationDate), message)
    })
    Item(wi.getId.toString, DateTime.parse(wi.getCreationDate), wi.getItem.toByteArray, attempts)
  }
}

class DelayedItemWorker(q: WorkQueueClient) extends Runnable with Log {
  private val executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
    override def newThread(r: Runnable) = new Thread(r, "backup-delayed-item-worker")
  })

  // TODO Make this fire on the minute every minute (for my OCD)
  def start(): Unit = executorService.scheduleWithFixedDelay(this, 10l, 60l, SECONDS)

  def stop(): Unit = executorService.shutdown()

  override def run(): Unit = {
    try {
      q.undelayAllReadyToSubmit()
    } catch {
      case e: Exception => log.error(e.getMessage, e)
    }
  }
}

abstract class RedisWorkQueueWorker[T](
    qname: String,
    val redisClient: RedisClient,
    maxProcessMs: Int = 4000,
    maxAttempts: Int = 2,
    delayBetweenAttemptsMs: Int = 10)
  extends Runnable
  with RedisClientHelper
  with Log
{
  private val executor = Executors.newSingleThreadExecutor(new ThreadFactory {
    override def newThread(r: Runnable) = new Thread(r, s"${qname}-worker")
  })

  def start(): Unit = executor.submit(this)

  def stop(): Unit = {
    println("stopping...")
    log.info("stopping")
    executor.shutdownNow().foreach(r => {
      //log.info("{}", r)
      println(r)
    })
  }

  def blockingProcessNext() = {
    withJedis(jedis => {
      jedis.blpop(1024, qname.pending) match {
        case null => // Do nothing
        case vs: JList[Array[Byte]] => vs.toList match {
          case keyName :: id :: Nil => {
            val itemBuilder = WQItem.parseFrom(jedis.hget(qname.items, id)).toBuilder

            // Set default values
            if (!itemBuilder.hasMaxProcessMs) {
              itemBuilder.setMaxProcessMs(maxProcessMs)
            }

            if (!itemBuilder.hasMaxAttempts) {
              itemBuilder.setMaxAttempts(maxAttempts)
            }

            if (!itemBuilder.hasDelayBetweenAttemptsMs) {
              itemBuilder.setDelayBetweenAttemptsMs(delayBetweenAttemptsMs)
            }
            // END Set default values

            val item = itemBuilder
                .setInProgressAttempt(WQItem.Attempt.newBuilder.setCreationDate(DateTime.now().toString))
                .build

            jedis.hset(qname.items, id, item.toByteArray)
            jedis.rpush(qname.inProgress, id)

            // Process the item
            var error: Throwable = null
            val t = deserialize(item.getItem.toByteArray)
            try {
              process(t)
            } catch {
              case e: Exception => {
                error = e
              }
            } finally {
              try {
                // Remove from in-progress
                jedis.lrem(qname.inProgress, 0, id) // Should return 1

                if (null == error) {
                  jedis.hdel(qname.items, id) // Should return 1
                } else {
                  val errorMessage = "%s: [%s]".format(error.getClass.getSimpleName, error.getMessage)
                  //log.info("errorMessage [{}}", errorMessage)
                  log.warn(error.getMessage, error)
                  
                  val attempt = item.getInProgressAttempt
                  val newItem = item.toBuilder
                      .clearInProgressAttempt()
                      .addAttempt(attempt.toBuilder.setMessage(errorMessage))
                      .build
                  jedis.hset(qname.items, id, newItem.toByteArray)

                  val attemptCount = item.getAttemptList.size + 1
                  if (attemptCount >= item.getMaxAttempts) {
                    log.error("Work queue failure [{}] [{}]", Array(qname, errorMessage): _*)
                    // Move to fails
                    jedis.rpush(qname.fails, id)
                  } else {
                    // TODO Delay the retry
                    // Add back to pending
                    log.info("Rolling back item [{}] [{}]", Array(qname, errorMessage): _*)
                    jedis.rpush(qname.pending, id)
                  }
                }
              } catch {
                case e: Exception => {
                  log.error("Unexpected exception [{}]", e.getMessage)
                  //log.error(e.getMessage, e)
                }
              }
            }
          }
          case _ => sys.error("Unexpected result")
        }
      }
    })
  }

  override def run(): Unit = {
    val MaxBackoffSleepTime = 20l

    var backoffSleepTime = 1l
    while (true) {
      try {
        blockingProcessNext()
        backoffSleepTime = 1l
      } catch {
        case e: Exception => {
          try {
            log.warn(e.getMessage(), e)
            val t = MaxBackoffSleepTime.min(backoffSleepTime)
            log.info("Sleeping for [{}] seconds", t)
            Thread.sleep(SECONDS.toMillis(t))
          } catch {
            case e: InterruptedException => log.warn(e.getMessage, e)
          }
          backoffSleepTime = backoffSleepTime * 2
        }
      }
    }
  }

  protected def process(item: T) = ()

  protected def deserialize(bytes: Array[Byte]): T
}
