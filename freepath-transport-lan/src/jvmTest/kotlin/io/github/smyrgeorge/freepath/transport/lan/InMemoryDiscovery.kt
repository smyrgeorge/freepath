package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(DelicateCoroutinesApi::class)
class InMemoryDiscovery : PeerDiscovery {

    private val peers = mutableMapOf<String, String>()
    private val listeners = mutableListOf<suspend (String, String) -> Unit>()

    fun register(nodeId: String, address: String) {
        peers[nodeId] = address
        listeners.forEach { listener ->
            GlobalScope.launch {
                listener(nodeId, address)
            }
        }
    }

    override fun start(onPeerDiscovered: suspend (String, String) -> Unit) {
        listeners += onPeerDiscovered
        peers.forEach { (id, address) ->
            GlobalScope.launch {
                onPeerDiscovered(id, address)
            }
        }
    }

    override fun stop() {}
}
