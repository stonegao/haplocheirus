package com.twitter.haplocheirus

import java.util.concurrent.{ConcurrentHashMap, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import com.twitter.ostrich.Stats
import com.twitter.util.Time
import com.twitter.util.TimeConversions._
import com.twitter.gizzard.shards.{ShardInfo, ShardBlackHoleException}
import net.lag.logging.Logger
import org.jredis.ClientRuntimeException

class RedisPoolHealthTracker(config: RedisPoolHealthTrackerConfig) {
  val log = Logger(getClass.getName)

  val concurrentErrorMap = new ConcurrentHashMap[String, AtomicInteger]
  val concurrentDisabledMap = new ConcurrentHashMap[String, Time]

  def countError(shardInfo: ShardInfo, client: PipelinedRedisClient) = {
    val hostname = shardInfo.hostname
    var count = concurrentErrorMap.get(hostname)
    if (count eq null) {
      val newCount = new AtomicInteger()
      count = concurrentErrorMap.putIfAbsent(hostname, newCount)
      if (count eq null) {
        count = newCount
      }
    }
    val c = count.incrementAndGet
    if (c > config.autoDisableErrorLimit && !concurrentDisabledMap.containsKey(hostname)) {
      log.error("Autodisabling %s", hostname)
      concurrentDisabledMap.put(hostname, config.autoDisableDuration.fromNow)
    }

    if (client ne null) {
      val clientC = client.errorCount.incrementAndGet
      if (clientC > config.clientErrorLimit) {
        log.error("Too many errors on client for %s", hostname)
      }
    }
  }

  def countNonError(shardInfo: ShardInfo, client: PipelinedRedisClient) = {
    val hostname = shardInfo.hostname
    if (concurrentErrorMap.containsKey(hostname)) {
      try {
        concurrentErrorMap.remove(hostname)
      } catch {
        case e: NullPointerException => {}
      }
    }
    client.errorCount.set(0)
  }

  def isErrored(shardInfo: ShardInfo, client: PipelinedRedisClient): Boolean = {
    val hostname = shardInfo.hostname
    val timeout = concurrentDisabledMap.get(hostname)
    val hostnameErrored = if (!(timeout eq null)) {
      if (Time.now < timeout) {
        true
      } else {
        try {
          concurrentDisabledMap.remove(hostname)
          log.error("Reenabling %s", hostname)
          countNonError(shardInfo, client) // To remove from the error map
        } catch {
          case e: NullPointerException => {}
        }
        false
      }
    } else {
      false
    }
    val clientErrored = if ((client ne null) && (client.errorCount.get > config.clientErrorLimit)) {
      true
    } else {
      false
    }
    hostnameErrored || clientErrored
  }
}

class RedisPool(name: String, healthTracker: RedisPoolHealthTracker, config: RedisPoolConfig) {
  val log = Logger(getClass.getName)
  val exceptionLog = Logger.get("exception")

  val concurrentServerMap = new ConcurrentHashMap[String, PipelinedRedisClient]
  val serverMap = scala.collection.jcl.Map(concurrentServerMap)

  def makeClient(hostname: String) = {
    val timeout = config.timeoutMsec.milliseconds
    val keysTimeout = config.keysTimeoutMsec.milliseconds
    val expiration = config.expirationHours.hours
    new PipelinedRedisClient(hostname, config.pipeline, timeout, keysTimeout, expiration)
  }

  def get(shardInfo: ShardInfo): PipelinedRedisClient = {
    val hostname = shardInfo.hostname
    var client = concurrentServerMap.get(hostname);

    if (healthTracker.isErrored(shardInfo, client)) {
      if (client ne null) {
        throwAway(hostname, client)
      }
      throw new ShardBlackHoleException(shardInfo.id)
    }

    if(client eq null) {
      val newClient = makeClient(hostname)
      client = concurrentServerMap.putIfAbsent(hostname, newClient);
      if(client eq null) {
        client = newClient
      }
    }
    client
  }

  def throwAway(hostname: String, client: PipelinedRedisClient) {
    try {
      client.shutdown()
    } catch {
      case e: Throwable =>
        exceptionLog.warning(e, "Error discarding dead redis client: %s", e)
    }
    try {
      concurrentServerMap.remove(hostname)
    } catch {
      case e: NullPointerException => {}
    }
  }

  def giveBack(hostname: String, client: PipelinedRedisClient) {
    if (!client.alive) {
      log.error("giveBack failed %s", hostname)
    }
  }

  def withClient[T](shardInfo: ShardInfo)(f: PipelinedRedisClient => T): T = {
    var client: PipelinedRedisClient = null
    val hostname = shardInfo.hostname
    try {
      client = Stats.timeMicros("redis-acquire-usec") { get(shardInfo) }
    } catch {
      case e: ShardBlackHoleException =>
        throw e
      case e =>
        healthTracker.countError(shardInfo, client)
        throw e
    }
    val r = try {
      f(client)
    } catch {
      case e: ClientRuntimeException =>
        exceptionLog.error(e, "Redis client error: %s", e)
        healthTracker.countError(shardInfo, client)
        throwAway(hostname, client)
        throw e
      case e: TimeoutException =>
        Stats.incr("redis-timeout")
        exceptionLog.warning(e, "Redis request timeout: %s", e)
        healthTracker.countError(shardInfo, client)
        throw e
      case e: Throwable =>
        exceptionLog.error(e, "Non-redis error: %s", e)
        healthTracker.countError(shardInfo, client)
        throw e
    } finally {
      Stats.timeMicros("redis-release-usec") { giveBack(hostname, client) }
    }

    healthTracker.countNonError(shardInfo, client)
    r
  }

  def shutdown() {
    serverMap.foreach { case (hostname, client) =>
      try {
        client.shutdown()
      } catch {
        case e: Throwable =>
          exceptionLog.error(e, "Failed to shutdown client: %s", e)
      }
    }
    serverMap.clear()
  }

  override def toString = {
    "<RedisPool: %s>".format(serverMap.map { case (hostname, client) =>
      "%s".format(hostname)
    }.mkString(", "))
  }
}
