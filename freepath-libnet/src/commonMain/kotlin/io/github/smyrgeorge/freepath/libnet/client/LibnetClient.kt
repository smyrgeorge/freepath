package io.github.smyrgeorge.freepath.libnet.client

import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.content.Message
import kotlin.random.Random

interface LibnetClient {
    fun start()
    fun stop()

    suspend fun send(
        message: Message,
        receiverId: String,
        reqId: Long = Random.nextLong(),
        onFrameSent: (reqId: Long, frameIndex: Int, frameCount: Int) -> Unit = { _, _, _ -> },
    ): Result<Unit>

    suspend fun send(
        content: Content,
        receiverId: String,
        reqId: Long = Random.nextLong(),
        onFrameSent: (reqId: Long, frameIndex: Int, frameCount: Int) -> Unit = { _, _, _ -> },
    ): Result<Unit>

    suspend fun relay(
        payload: ByteArray,
        receiverId: String,
        reqId: Long = Random.nextLong(),
    ): Result<Unit>
}
