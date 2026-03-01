package io.github.smyrgeorge.freepath.transport.lan

import io.ktor.network.sockets.Socket

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual object SocketUtils {
    actual fun trySetKeepAlive(socket: Socket) {
        // Ktor/Native does not expose the underlying POSIX file descriptor through its
        // public API, so SO_KEEPALIVE cannot be set on accepted sockets on iOS.
        // Outbound connections already have keep-alive enabled via the `keepAlive = true`
        // option in LanLinkAdapter's connect builder; only inbound connections are affected.
    }
}
