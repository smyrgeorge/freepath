package io.github.smyrgeorge.freepath.libnet

import kotlinx.coroutines.channels.ReceiveChannel

interface LibnetModule {
    val requests: ReceiveChannel<NetRequest>

    fun start(peerId: String)
    fun stop()

    suspend fun request(
        reqId: Long,
        peerId: String,
        payload: ByteArray,
        onFrameSent: (reqId: Long, frameIndex: Int, frameCount: Int) -> Unit,
    ): Result<ByteArray>

    suspend fun sendResponse(reqId: Long, payload: ByteArray)
    suspend fun sendResponseFailed(reqId: Long, error: String)
}
