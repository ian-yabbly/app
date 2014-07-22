import com.studio6.app.common.Predef._
import com.studio6.app.common.SecurityUtils._
import com.studio6.app.common.redis._

import redis.clients.jedis.JedisPool

import java.util.concurrent._
import java.util.concurrent.TimeUnit._

object LockTest {
  val ThreadCount = 4
  val IterationCount = 128

  def main(args: Array[String]): Unit = {
    val jedisPool = new JedisPool("localhost", 6379)

    val client = new AnyRef with RedisClientHelper {
      val redisClient = new RedisClientImpl(jedisPool, 0, 32)
    }

    val executor = Executors.newFixedThreadPool(ThreadCount)
    val lockName = "test.lock.%s".format(randomAlphanumeric(16))
    val keyName = "test.key.%s".format(randomAlphanumeric(16))

    try {
      0.until(ThreadCount).foreach(i => {
        executor.submit(new Runnable {
          override def run(): Unit = {
            try {
              0.until(IterationCount).foreach(j => {
                print('.')
                client.withJedis(jedis => client.inLock(jedis, lockName, 100l) {
                  val n = jedis.get(keyName) match {
                        case null => 0
                        case s: String => s.toInt
                      }
                  Thread.sleep(randomPositiveInt % 60)
                  jedis.set(keyName, (n+1).toString)
                })
              })
            } catch {
              case e: Exception => e.printStackTrace
            }
          }
        })
      })

      executor.shutdown()
      executor.awaitTermination(1l, MINUTES)
      println

      client.withJedis(jedis => {
        val n = jedis.get(keyName).toInt
        assert(n == (ThreadCount * IterationCount))
      })
    } catch {
      case e: Exception => e.printStackTrace
    } finally {
      client.withJedis(jedis => {
        jedis.del(keyName)
      })
    }
  }
}

LockTest.main(args)
