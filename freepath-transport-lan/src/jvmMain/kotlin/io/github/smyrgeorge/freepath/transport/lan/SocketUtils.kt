package io.github.smyrgeorge.freepath.transport.lan

import io.ktor.network.sockets.Socket
import java.net.StandardSocketOptions
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.SocketChannel

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual object SocketUtils {
    actual fun trySetKeepAlive(socket: Socket) {
        try {
            var cls: Class<*>? = socket.javaClass
            while (cls != null && cls != Any::class.java) {
                for (field in cls.declaredFields) {
                    if (!field.trySetAccessible()) continue
                    val value = try {
                        field.get(socket)
                    } catch (_: Throwable) {
                        continue
                    }
                    when (value) {
                        is java.net.Socket -> {
                            value.keepAlive = true
                            return
                        }

                        is SocketChannel -> {
                            value.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
                            return
                        }

                        is AsynchronousSocketChannel -> {
                            value.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
                            return
                        }
                    }
                }
                cls = cls.superclass
            }
        } catch (_: Throwable) {
            // Ktor internals changed or strong module encapsulation blocked access;
            // SO_KEEPALIVE simply won't be set on this connection.
        }
    }
}
