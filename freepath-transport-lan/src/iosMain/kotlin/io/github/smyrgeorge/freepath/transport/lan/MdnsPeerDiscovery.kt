@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.smyrgeorge.freepath.transport.lan

import MdnsBridge.MdnsBridge
import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MdnsPeerDiscovery(override val nodeId: String) : PeerDiscovery {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bridge = MdnsBridge(nodeId)

    override suspend fun start(port: Int, onPeerDiscovered: suspend (String, String) -> Unit) {
        bridge.startWithPort(port) { peerId, address ->
            if (peerId != null && address != null) {
                scope.launch { onPeerDiscovered(peerId, address) }
            }
        }
    }

    override suspend fun stop() {
        bridge.stop()
    }
}
