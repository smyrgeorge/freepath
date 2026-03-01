package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.lan.LanLinkAdapter.Companion.MAX_INBOUND_CONNECTIONS
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LanServer(
    private val bindHost: String = "0.0.0.0",
    private val bindPort: Int = 0,
    private val maxInboundConnections: Int = MAX_INBOUND_CONNECTIONS,
) {
    private var serverSocket: ServerSocket? = null
    private var selectorManager: SelectorManager? = null
    private var acceptJob: Job? = null
    private var connectionCount = 0
    private val mutex = Mutex()

    val localPort: Int
        get() = (serverSocket?.localAddress as? InetSocketAddress)?.port
            ?: error("Server not started")

    suspend fun start(scope: CoroutineScope, onConnection: suspend (LanConnection) -> Unit) {
        mutex.withLock {
            check(serverSocket == null) { "Server already started" }
            val sm = SelectorManager(Dispatchers.IO)
            selectorManager = sm
            val srv = aSocket(sm).tcp().bind(bindHost, bindPort)
            serverSocket = srv

            acceptJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    val socket = try {
                        srv.accept()
                    } catch (_: Exception) {
                        break
                    }
                    // Spec (6-transport-lan.md): SHOULD enable TCP keep-alive on all
                    // Freepath connections. Ktor has no public API for accepted sockets,
                    // so we use a platform-specific best-effort helper.
                    SocketUtils.trySetKeepAlive(socket)

                    mutex.withLock {
                        if (connectionCount >= maxInboundConnections) {
                            socket.close()
                            return@withLock
                        }
                        connectionCount++
                        launch(Dispatchers.IO) {
                            try {
                                onConnection(LanConnection(socket))
                            } finally {
                                mutex.withLock { connectionCount-- }
                                socket.close()
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun stop() {
        mutex.withLock {
            acceptJob?.cancel()
            serverSocket?.close()
            selectorManager?.close()
            serverSocket = null
            selectorManager = null
            acceptJob = null
        }
    }
}
