package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import io.github.smyrgeorge.freepath.util.AndroidContextHolder

actual fun createPeerDiscovery(nodeId: String): PeerDiscovery =
    MdnsPeerDiscovery(nodeId, AndroidContextHolder.applicationContext)
