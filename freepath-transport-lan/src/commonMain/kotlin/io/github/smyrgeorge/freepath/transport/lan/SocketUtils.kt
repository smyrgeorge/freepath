package io.github.smyrgeorge.freepath.transport.lan

import io.ktor.network.sockets.Socket

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect object SocketUtils {
    fun trySetKeepAlive(socket: Socket)
}
