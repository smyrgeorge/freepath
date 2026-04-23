package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.freepath.libp2p.metrics.Libp2pMetrics
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

interface Libp2pModule {
    val metrics: Libp2pMetrics
    val requests: Channel<Libp2pEvent.RequestReceived>

    fun setEventHandler(handler: suspend (Libp2pEvent) -> Unit): Libp2pModule

    suspend fun start(
        peerId: String,
        sigKeyPrivate: ByteArray,
        listenAddrs: String,
        relayAddrs: String,
        contactLookup: (String) -> Boolean = { false },
    )

    suspend fun stop()
    suspend fun dial(multiaddr: String)
    suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray)
    suspend fun sendResponse(reqId: Long, payload: ByteArray)
    suspend fun sendResponseFailed(reqId: Long, error: String)
    suspend fun request(timeout: Duration, peerId: String, payload: ByteArray): Libp2pEvent.Response
}
