package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

@OptIn(DelicateCoroutinesApi::class)
class InMemoryDiscovery(override val nodeId: String = "") : PeerDiscovery {

    private val peers = mutableMapOf<String, String>()
    private val listeners = mutableListOf<suspend (String, String) -> Unit>()

    fun register(nodeId: String, address: String) {
        peers[nodeId] = address
        listeners.forEach { listener ->
            GlobalScope.launch(Dispatchers.IO) {
                listener(nodeId, address)
            }
        }
    }

    override suspend fun start(port: Int, onPeerDiscovered: suspend (String, String) -> Unit) {
        listeners += onPeerDiscovered
        peers.forEach { (id, address) ->
            GlobalScope.launch(Dispatchers.IO) {
                onPeerDiscovered(id, address)
            }
        }
    }

    override suspend fun stop() {}
}
