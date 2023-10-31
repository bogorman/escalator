package escalator.util.redis

import redis.clients.jedis._

object RedisConnection {

  val host = "localhost"
  val port = 6379
  val password = ""

  // val password = if (config.hasPath("password")) config.getString("password").trim else ""
  // val namespace = if (config.hasPath("namespace")) config.getString("namespace").trim else ""

  val maxConnections = 500
  val timeout = 60000

  // println("Redis Host:" + host)
  // println("Redis Port:" + port)
  // println("Redis Password:" + password)
  // println("Redis Namepsace:" + namespace)

  lazy val clients = {
    val jedisConfig = new JedisPoolConfig
    jedisConfig.setTestOnBorrow(true)
    jedisConfig.setTestOnReturn(true)
    jedisConfig.setMaxIdle(maxConnections)
    jedisConfig.setTestWhileIdle(true)
    jedisConfig.setMaxTotal(maxConnections)
    jedisConfig.setMaxWaitMillis(timeout)

    if (password.isEmpty) {
      new Pool(new JedisPool(jedisConfig, host, port, timeout))
    } else {
      val jpool = new JedisPool(jedisConfig, host, port, timeout, password)
      new Pool(jpool)
    }
  }

}