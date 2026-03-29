package io.github.smyrgeorge.freepath.libp2p.util

import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent.Response
import io.github.smyrgeorge.freepath.libp2p.lanAddressToMultiaddr
import io.github.smyrgeorge.freepath.libp2p.metrics.Libp2pMetrics
import io.github.smyrgeorge.freepath.util.rpc.RpcManager
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    val metrics: Libp2pMetrics = Libp2pMetrics()

    init {
        require(requestTimeout > Duration.ZERO) { "Request timeout must be positive" }
        scope.launch {
            while (true) {
                delay(30.seconds)
                metrics.value.value.mdnsPeers.values.forEach { address ->
                    val multiaddr = lanAddressToMultiaddr(address) ?: return@forEach
                    log.debug { "Keep-alive: dialling $multiaddr mDNS peer" }
                    runCatching { dial(multiaddr) }
                        .onFailure { log.warn { "Keep-alive dial failed for $address: $it" } }
                }
            }
        }
    }

    abstract suspend fun dial(multiaddr: String)
    abstract suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray)

    suspend fun request(
        timeout: Duration,
        peerId: String,
        payload: ByteArray
    ): Response = rpc.request(timeout) { sendRequest(peerId, it, payload) }
}
