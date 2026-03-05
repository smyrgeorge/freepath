@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.smyrgeorge.freepath.transport.lan

import MdnsBridge.MdnsBridge
import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MdnsPeerDiscovery(override val nodeId: String) : PeerDiscovery {

    private val log = Logger.of(MdnsPeerDiscovery::class)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bridge = MdnsBridge(nodeId)

    override suspend fun start(port: Int, onPeerDiscovered: suspend (String, String) -> Unit) {
        log.info { "mDNS starting on port $port (nodeId=$nodeId)" }
        bridge.startWithPort(port) { peerId, address ->
            if (peerId != null && address != null) {
                log.info { "mDNS peer discovered: $peerId at $address" }
                scope.launch { onPeerDiscovered(peerId, address) }
            }
        }
    }

    override suspend fun stop() {
        bridge.stop()
    }
}
