package ru.killwolfvlad.redis.internal

import io.ktor.http.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.logging.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readString
import kotlin.time.Duration.Companion.milliseconds

internal class RedisConnection(
    rootJob: Job,
    connectionName: String,
    private val redisConnectionString: String,
) {
    private data class RequestPayload(
        val command: String,
        val arguments: List<String>,
        val request: String,
        val responseChannel: Channel<String>,
    )

    private val logger = KtorSimpleLogger(connectionName)

    private val coroutineScope = CoroutineScope(
        rootJob + Dispatchers.IO + CoroutineName(RedisConnection::class.simpleName + "Coroutine"),
    )

    private lateinit var selectorManager: SelectorManager
    private lateinit var socket: Socket
    private lateinit var readChannel: ByteReadChannel
    private lateinit var writeChannel: ByteWriteChannel

    private val responsesSharedFlow = MutableSharedFlow<RequestPayload>()
    private lateinit var readResponsesJob: Job

    private lateinit var flushDelayJob: Job
    private lateinit var flushSizeJob: Job

    private val requestsMutex = Mutex()
    private val requestsQueue = Channel<RequestPayload>(Channel.UNLIMITED)

    suspend fun init() {
        val url = Url(redisConnectionString)

        selectorManager = SelectorManager(Dispatchers.IO)

        socket = aSocket(selectorManager).tcp().connect(url.host, url.port)

        readChannel = socket.openReadChannel()
        writeChannel = socket.openWriteChannel()

        readResponsesJob = coroutineScope.launch {
            responsesSharedFlow.collect { requestPayload ->
                readResponse(requestPayload)
            }
        }

        val requests = mutableListOf<RequestPayload>()

        flushDelayJob = coroutineScope.launch {
            while (true) {
                delay(10.milliseconds)

                requestsMutex.withLock {
                    if (requests.isNotEmpty()) {
                        flush(requests)

                        requests.clear()
                    }
                }
            }
        }

        flushSizeJob = coroutineScope.launch {
            while (true) {
                val request = requestsQueue.receive()

                requestsMutex.withLock {
                    requests.add(request)

                    if (requests.size >= 10) {
                        flush(requests)

                        requests.clear()
                    }
                }
            }
        }
    }

    fun close() {
        runCatching {
            flushDelayJob.cancel()
            flushSizeJob.cancel()
            readResponsesJob.cancel()

            socket.close()
            selectorManager.close()
        }
    }

    suspend fun execute(command: String, vararg arguments: String): String {
        val responseChannel = Channel<String>(1)

        addRequestToQueue(responseChannel, command, *arguments)

        val response = responseChannel.receive()

        return response
    }

    private suspend fun addRequestToQueue(
        responseChannel: Channel<String>,
        command: String,
        vararg arguments: String,
    ) {
        val request =
            "*${1 + arguments.size}\r\n${
                listOf(command, *arguments).joinToString("\r\n") { "$${it.length}\r\n$it" }
            }\r\n"

        val requestPayload = RequestPayload(command, arguments.toList(), request, responseChannel)

        requestsQueue.send(requestPayload)

        logger.trace("ADD TO QUEUE: $command ${arguments.joinToString(" ")}")
    }

    private suspend fun readResponse(requestPayload: RequestPayload) {
        val type = readChannel.readLineCRLF().readString(Charsets.UTF_8)

        if (type == "+OK") {
            val response = "OK"

            requestPayload.responseChannel.send(response)
            logger.trace("READ: ${requestPayload.command} ${requestPayload.arguments.joinToString(" ")} = $response")

            return
        }

        val response = if (type == "$-1") "" else readChannel.readLineCRLF().readString(Charsets.UTF_8)

        requestPayload.responseChannel.send(response)
        logger.trace("READ: ${requestPayload.command} ${requestPayload.arguments.joinToString(" ")} = $response")
    }

    private suspend fun flush(requests: List<RequestPayload>) {
        writeChannel.writeStringUtf8(requests.joinToString("") { it.request })
        writeChannel.flush()

        logger.trace("FLUSH: requests size = ${requests.size}")

        requests.forEach { responsesSharedFlow.emit(it) }
    }
}

val CARRIAGE_RETURN_BYTE = '\r'.code.toByte()
val NEWLINE_BYTE = '\n'.code.toByte()

private suspend inline fun ByteReadChannel.readLineCRLF(): kotlinx.io.Buffer {
    val buffer = kotlinx.io.Buffer()
    while (true) {
        val byte = readByte()

        if (byte == CARRIAGE_RETURN_BYTE) {
            val nextByte = readByte()
            if (nextByte == NEWLINE_BYTE) {
                break
            } else {
                buffer.writeByte(CARRIAGE_RETURN_BYTE)
                buffer.writeByte(NEWLINE_BYTE)
                continue
            }
        }
        buffer.writeByte(byte)
    }
    return buffer
}
