package io.github.smyrgeorge.freepath.libp2p.util

import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent.Response
import io.github.smyrgeorge.freepath.util.rpc.RpcManager
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

abstract class AbstractLibp2pModule(
    requestTimeout: Duration
) {
    internal val log = Logger.of(this::class)
    internal val rpc = RpcManager<Response>(requestTimeout)

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val requests: Channel<Libp2pEvent.RequestReceived> = Channel(
        capacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_LATEST
    )

    init {
        require(requestTimeout > Duration.ZERO) { "Request timeout must be positive" }
    }

    abstract suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray)

    suspend fun request(peerId: String, payload: ByteArray): Response =
        rpc.request { sendRequest(peerId, it, payload) }
}