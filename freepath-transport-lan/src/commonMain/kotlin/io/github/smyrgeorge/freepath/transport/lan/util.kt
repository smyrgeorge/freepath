package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery

expect fun createPeerDiscovery(nodeId: String): PeerDiscovery
