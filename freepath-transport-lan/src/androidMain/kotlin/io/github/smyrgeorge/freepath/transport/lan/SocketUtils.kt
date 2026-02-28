package io.github.smyrgeorge.freepath.transport.lan

import io.ktor.network.sockets.Socket

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual object SocketUtils {
    actual fun trySetKeepAlive(socket: Socket) {
        // Walk Ktor's internal field hierarchy looking for the underlying java.net.Socket.
        // trySetAccessible() requires API 26+, which matches this module's minSdk.
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
}
