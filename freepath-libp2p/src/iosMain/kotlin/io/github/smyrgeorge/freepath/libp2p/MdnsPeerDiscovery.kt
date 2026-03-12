@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.freepath.libp2p

import MdnsBridge.MdnsBridge
import io.github.smyrgeorge.log4k.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class MdnsPeerDiscovery(val nodeId: String) {

    private val log = Logger.of(this::class)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bridge = MdnsBridge(nodeId)

    fun start(
        port: Int,
        onPeerDiscovered: suspend (peerId: String, address: String) -> Unit,
        onPeerRemoved: suspend (peerId: String) -> Unit,
    ) {
        log.info { "mDNS starting on port $port (nodeId=$nodeId)" }
        bridge.setOnPeerRemoved { peerId: String? ->
            val id = peerId ?: return@setOnPeerRemoved
            log.debug { "mDNS peer removed: $id" }
            scope.launch { onPeerRemoved(id) }
        }
        bridge.startWithPort(port) { peerId, address ->
            if (peerId != null && address != null) {
                log.debug { "mDNS peer discovered: $peerId at $address" }
                scope.launch { onPeerDiscovered(peerId, address) }
            }
        }
    }

    fun stop() {
        bridge.stop()
    }
}
