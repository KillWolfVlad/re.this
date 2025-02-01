package ru.killwolfvlad.redis

import kotlinx.coroutines.SupervisorJob
import ru.killwolfvlad.redis.internal.RedisConnection

class RedisClient(
    redisConnectionString: String,
    overdriveMode: Boolean,
    private val poolMode: Boolean,
) {
    /**
     * Created by Oleksandr Loushkin 2018-05-16
     * @see <a href="https://gist.github.com/laviua/e8759ce693d2489faebdd9249e62978b">GIST</a>
     * @see <a href="https://stackoverflow.com/a/53546406">STACKOVERFLOW</a>
     */
    class RoundRobinIterable<out T>(private val collection: Iterable<T>) {
        private var iterator: Iterator<T> = collection.iterator()

        @Synchronized
        fun getNext(): T {
            if (!iterator.hasNext()) {
                iterator = collection.iterator()
            }
            return iterator.next()
        }
    }

    private val rootJob = SupervisorJob()

    private var connection: RedisConnection =
        RedisConnection(rootJob, "SingleConnection", overdriveMode, redisConnectionString)

    private val connectionsPool = List(50) {
        RedisConnection(rootJob, "PoolConnection${it}", overdriveMode, redisConnectionString)
    }

    private val connectionsPoolRoundRobit = RoundRobinIterable(connectionsPool)

    suspend fun init() {
        if (poolMode) {
            connectionsPool.forEach { it.init() }
        } else {
            connection.init()
        }
    }

    fun close() {
        if (poolMode) {
            connectionsPool.forEach { it.close() }
        } else {
            connection.close()
        }
    }

    suspend fun execute(command: String, vararg arguments: String): String {
        return if (poolMode) {
            connectionsPoolRoundRobit.getNext().execute(command, *arguments)
        } else {
            connection.execute(command, *arguments)
        }
    }
}
