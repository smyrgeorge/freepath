package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery

actual fun createPeerDiscovery(nodeId: String): PeerDiscovery = MdnsPeerDiscovery(nodeId)
