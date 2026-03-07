package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import io.github.smyrgeorge.freepath.util.AndroidContextHolder
import io.ktor.network.sockets.Socket

actual fun createPeerDiscovery(nodeId: String): PeerDiscovery =
    MdnsPeerDiscovery(nodeId, AndroidContextHolder.applicationContext)

// Walk Ktor's internal field hierarchy looking for the underlying java.net.Socket.
// trySetAccessible() requires API 26+, which matches this module's minSdk.
actual fun trySetKeepAlive(socket: Socket) {
    try {
        var cls: Class<*>? = socket.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                try {
                    field.isAccessible = true
                } catch (_: Exception) {
                    continue
                }
                val value = try {
                    field.get(socket)
                } catch (_: Throwable) {
                    continue
                }
                if (value is java.net.Socket) {
                    value.keepAlive = true
                    return
                }
            }
            cls = cls.superclass
        }
    } catch (_: Throwable) {
        // Gracefully degrade — SO_KEEPALIVE simply won't be set on this connection.
    }
}
