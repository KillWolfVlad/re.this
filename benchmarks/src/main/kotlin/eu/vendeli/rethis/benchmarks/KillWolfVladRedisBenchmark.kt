package eu.vendeli.rethis.benchmarks

import com.redis.testcontainers.RedisContainer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.testcontainers.utility.DockerImageName
import ru.killwolfvlad.redis.RedisClient
import java.util.concurrent.TimeUnit

@DelicateCoroutinesApi
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Timeout(time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1, jvmArgsAppend = ["-Xms12g", "-Xmx12g", "-Xss2m", "-XX:MaxMetaspaceSize=1g"])
class KillWolfVladRedisBenchmark {
    private val redis = RedisContainer(
        DockerImageName.parse("redis:7.4.0"),
    )

    private lateinit var redisClient: RedisClient
    private lateinit var redisClientOverdrive: RedisClient
    private lateinit var redisClientPool: RedisClient
    private lateinit var redisClientPoolOverdrive: RedisClient

    @Setup
    fun setup() {
        redis.start()

        val redisConnectionString = "redis://${redis.host}:${redis.firstMappedPort}"

        redisClient = RedisClient(redisConnectionString, overdriveMode = false, poolMode = false)
        redisClientOverdrive = RedisClient(redisConnectionString, overdriveMode = true, poolMode = false)
        redisClientPool = RedisClient(redisConnectionString, overdriveMode = false, poolMode = true)
        redisClientPoolOverdrive = RedisClient(redisConnectionString, overdriveMode = true, poolMode = true)

        GlobalScope.launch {
            redisClient.init()
            redisClientOverdrive.init()
            redisClientPool.init()
            redisClientPoolOverdrive.init()
        }
    }

    @TearDown
    fun tearDown() {
        redisClient.close()
        redisClientOverdrive.close()
        redisClientPool.close()
        redisClientPoolOverdrive.close()

        redis.stop()
    }

    @Benchmark
    fun killWolfVladRedisSetGet(bh: Blackhole) {
        val randInt = (1..10_000).random()

        GlobalScope.launch {
            bh.consume(redisClient.execute("SET", "keyKwRedis$randInt", "value$randInt"))
            val value = redisClient.execute("GET", "keyKwRedis$randInt")

            assert(value == "value$randInt")
        }
    }

    @Benchmark
    fun killWolfVladRedisOverdriveSetGet(bh: Blackhole) {
        val randInt = (1..10_000).random()

        GlobalScope.launch {
            bh.consume(redisClientOverdrive.execute("SET", "keyKwRedisOver$randInt", "value$randInt"))
            val value = redisClientOverdrive.execute("GET", "keyKwRedisOver$randInt")

            assert(value == "value$randInt")
        }
    }

    @Benchmark
    fun killWolfVladRedisPoolSetGet(bh: Blackhole) {
        val randInt = (1..10_000).random()

        GlobalScope.launch {
            bh.consume(redisClientPool.execute("SET", "keyKwRedisPool$randInt", "value$randInt"))
            val value = redisClientPool.execute("GET", "keyKwRedisPool$randInt")

            assert(value == "value$randInt")
        }
    }

    @Benchmark
    fun killWolfVladRedisPoolOverdriveSetGet(bh: Blackhole) {
        val randInt = (1..10_000).random()

        GlobalScope.launch {
            bh.consume(redisClientPoolOverdrive.execute("SET", "keyKwRedisPoolOver$randInt", "value$randInt"))
            val value = redisClientPoolOverdrive.execute("GET", "keyKwRedisPoolOver$randInt")

            assert(value == "value$randInt")
        }
    }
}
