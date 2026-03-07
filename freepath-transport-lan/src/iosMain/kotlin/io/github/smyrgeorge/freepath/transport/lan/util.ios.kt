package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import io.ktor.network.sockets.Socket

actual fun createPeerDiscovery(nodeId: String): PeerDiscovery = MdnsPeerDiscovery(nodeId)

// Ktor/Native does not expose the underlying POSIX file descriptor through its
// public API, so SO_KEEPALIVE cannot be set on accepted sockets on iOS.
// Outbound connections already have keep-alive enabled via the `keepAlive = true`
// option in LanLinkAdapter's connect builder; only inbound connections are affected.
actual fun trySetKeepAlive(socket: Socket) = Unit
