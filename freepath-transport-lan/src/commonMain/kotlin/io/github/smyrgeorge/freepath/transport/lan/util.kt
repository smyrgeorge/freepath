package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import io.ktor.network.sockets.Socket

expect fun createPeerDiscovery(nodeId: String): PeerDiscovery
expect fun trySetKeepAlive(socket: Socket)
