package ru.killwolfvlad.redis.internal

import io.ktor.http.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.logging.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.Buffer
import kotlinx.io.readString

internal class RedisConnection(
    rootJob: Job,
    connectionName: String,
    private val overdriveMode: Boolean,
    private val redisConnectionString: String,
) {
    private data class RequestPayload(
        val command: String,
        val arguments: List<String>,
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

    private val requestSharedFlow = MutableSharedFlow<RequestPayload>()
    private val responseSharedFlow = MutableSharedFlow<RequestPayload>()

    private lateinit var requestCollectJob: Job
    private lateinit var responseCollectJob: Job

    suspend fun init() {
        val url = Url(redisConnectionString)

        selectorManager = SelectorManager(Dispatchers.IO)

        socket = aSocket(selectorManager).tcp().connect(url.host, url.port)

        readChannel = socket.openReadChannel()
        writeChannel = socket.openWriteChannel()

        requestCollectJob = coroutineScope.launch {
            requestSharedFlow.collect { requestPayload ->
                writeRequest(requestPayload)
            }
        }

        responseCollectJob = coroutineScope.launch {
            responseSharedFlow.collect { requestPayload ->
                readResponse(requestPayload)
            }
        }
    }

    fun close() {
        runCatching {
            requestCollectJob.cancel()
            responseCollectJob.cancel()

            socket.close()
            selectorManager.close()
        }
    }

    suspend fun execute(command: String, vararg arguments: String): String {
        val responseChannel = Channel<String>(1)

        requestSharedFlow.emit(RequestPayload(command, arguments.toList(), responseChannel))
        logger.trace("EMIT: $command ${arguments.joinToString(" ")}")

        val response = responseChannel.receive()

        return response
    }

    private suspend fun writeRequest(requestPayload: RequestPayload) {
        val rawCommand =
            "*${1 + requestPayload.arguments.size}\r\n${
                listOf(
                    requestPayload.command,
                    *requestPayload.arguments.toTypedArray(),
                ).joinToString("\r\n") { "$${it.length}\r\n$it" }
            }\r\n"

        writeChannel.writeStringUtf8(rawCommand)
        writeChannel.flush()

        if (overdriveMode) {
            responseSharedFlow.emit(requestPayload)
            logger.trace("WRITE: ${requestPayload.command} ${requestPayload.arguments.joinToString(" ")}")
        } else {
            logger.trace("WRITE: ${requestPayload.command} ${requestPayload.arguments.joinToString(" ")}")
            readResponse(requestPayload)
        }
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
}

val CARRIAGE_RETURN_BYTE = '\r'.code.toByte()
val NEWLINE_BYTE = '\n'.code.toByte()

private suspend inline fun ByteReadChannel.readLineCRLF(): Buffer {
    val buffer = Buffer()
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
