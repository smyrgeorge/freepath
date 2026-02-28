package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.LinkAdapter
import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import io.github.smyrgeorge.freepath.transport.codec.Base58
import io.github.smyrgeorge.freepath.transport.lan.LanLinkAdapter.Companion.HANDSHAKE_TIMEOUT_MS
import io.github.smyrgeorge.freepath.transport.model.Frame
import io.github.smyrgeorge.freepath.transport.model.FrameType
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64

class LanLinkAdapter(
    private val peerDiscovery: PeerDiscovery,
    private val onPeerDisconnected: suspend (peerId: String) -> Unit,
    /**
     * Returns true if [nodeId] belongs to a known contact. Called before opening a TCP
     * connection to a discovered peer — unknown peers are silently ignored per spec step 2.
     */
    private val isKnownPeer: (nodeId: String) -> Boolean,
    /**
     * Called immediately after a new outbound TCP connection is registered, before the
     * receive loop starts. Implementations should initiate the protocol handshake here
     * (e.g. call StatefulProtocol.initiateHandshake(peerId)).
     */
    private val onConnectionEstablished: suspend (peerId: String) -> Unit,
) : LinkAdapter {

    private var inboundFrameHandler: (suspend (peerId: String, frame: Frame) -> Unit)? = null

    internal val nodeId: String get() = peerDiscovery.nodeId

    private val server = LanServer()

    private var selectorManager: SelectorManager? = null
    private var scope: CoroutineScope? = null

    /** Active connections keyed by peer nodeId (Base58-encoded). */
    private val connections = mutableMapOf<String, LanConnection>()

    /** Peer nodeIds for which an outbound TCP connect is in progress (not yet registered). */
    private val connecting = mutableSetOf<String>()
    private val connectionMutex = Mutex()

    /** The local TCP port the server is bound to. Only valid after [start]. */
    internal val localPort: Int get() = server.localPort

    // ---- Lifecycle -------------------------------------------------------

    override fun setInboundFrameHandler(handler: suspend (peerId: String, frame: Frame) -> Unit) {
        inboundFrameHandler = handler
    }

    override suspend fun closeConnection(peerId: String) {
        val connection = connectionMutex.withLock { connections.remove(peerId) } ?: return
        connection.close()
    }

    override suspend fun start() {
        checkNotNull(inboundFrameHandler) { "setInboundFrameHandler must be called before start()" }
        val job = SupervisorJob()
        val sc = CoroutineScope(Dispatchers.Default + job)
        scope = sc
        selectorManager = SelectorManager(Dispatchers.Default)

        server.start(sc) { connection ->
            handleInbound(connection)
        }

        // Advertise and discover
        peerDiscovery.start(server.localPort) { peerNodeId, address ->
            val (host, port) = LanPeerAddress.decode(address)
            sc.launch { connectToDiscoveredPeer(peerNodeId, host, port) }
        }
    }

    override suspend fun stop() {
        peerDiscovery.stop()
        server.stop()
        connectionMutex.withLock {
            connections.values.forEach { it.close() }
            connections.clear()
            connecting.clear()
        }
        withContext(Dispatchers.IO) {
            selectorManager?.close()
        }
        scope?.cancel()
    }

    // ---- Send ------------------------------------------------------------

    override suspend fun sendFrame(peerId: String, frame: Frame) {
        val connection = connectionMutex.withLock { connections[peerId] }
            ?: error("No active connection for peer $peerId")
        connection.sendFrame(frame)
    }

    // ---- Inbound ---------------------------------------------------------

    /**
     * Called for every connection accepted by the server.
     * The first frame must be a HANDSHAKE frame 0. We read it within [HANDSHAKE_TIMEOUT_MS],
     * extract the peer's Base58-encoded nodeId from the payload, register the connection,
     * then hand the frame to the protocol layer and continue reading in [receiveLoop].
     */
    private suspend fun handleInbound(connection: LanConnection) {
        try {
            val firstFrame = withTimeout(HANDSHAKE_TIMEOUT_MS) { connection.receiveFrame() } ?: return
            // The first frame on an inbound connection MUST be HANDSHAKE frame 0.
            // Reject anything else before touching the payload to avoid registering a connection
            // under a garbage peerId derived from a non-handshake payload.
            if (firstFrame.type != FrameType.HANDSHAKE) return
            // Extract sender nodeId from the handshake payload (bytes 64..80 per HandshakeHandler:
            // EPHEMERAL_KEY(32) | SIGKEY(32) | NODEID_RAW(16) | SIGNATURE(64))
            val rawPayload = Base64.decode(firstFrame.payload)
            if (rawPayload.size < 80) return
            val nodeIdRaw = rawPayload.copyOfRange(64, 80)
            val peerId = Base58.encode(nodeIdRaw)  // Base58 to match the rest of the system

            if (!registerConnection(peerId, connection)) return  // duplicate; new conn closed
            inboundFrameHandler?.invoke(peerId, firstFrame)
            receiveLoop(peerId, connection)
        } catch (_: Exception) {
            connection.close()
        }
    }

    internal suspend fun connectToDiscoveredPeer(nodeId: String, host: String, port: Int) {
        // Never connect to ourselves.
        if (nodeId == this.nodeId) return
        // Spec step 2: ignore peers not in the contact list — no TCP connection should be opened.
        if (!isKnownPeer(nodeId)) return
        // Spec step 3: skip if an active connection exists OR an outbound connect is already in
        // progress for this peer.  mDNS may fire multiple discovery events simultaneously
        // (e.g. IPv4 + IPv6 resolution), so we guard with a single atomic check.
        val proceed = connectionMutex.withLock {
            !connections.containsKey(nodeId) && connecting.add(nodeId)
        }
        if (!proceed) return

        val sm = selectorManager ?: return
        val socket = try {
            aSocket(sm).tcp().connect(InetSocketAddress(host, port)) {
                // Spec (6-transport-lan.md): SHOULD enable TCP keep-alive to detect silent peer
                // disconnection promptly, avoiding stale session state.
                keepAlive = true
            }
        } catch (_: Exception) {
            connectionMutex.withLock { connecting.remove(nodeId) }
            return
        }
        // TCP handshake done — the connection is now either registered or discarded.
        // Remove from `connecting` so that future mDNS events rely on `connections` alone.
        connectionMutex.withLock { connecting.remove(nodeId) }

        val connection = LanConnection(socket)

        if (!registerConnection(nodeId, connection)) return  // duplicate; new conn closed

        // Spec step 5: initiate the handshake now that the TCP connection is established.
        try {
            onConnectionEstablished(nodeId)
        } catch (_: Exception) {
            removeConnection(nodeId, connection)
            connection.close()
            return
        }

        try {
            receiveLoop(nodeId, connection, isOutbound = true)
        } finally {
            // Ensure the socket is released once the receive loop terminates.
            // Inbound connections are closed by LanServer's finally block; outbound
            // connections have no such wrapper, so we must close them explicitly here.
            connection.close()
        }
    }

    private suspend fun receiveLoop(
        peerId: String,
        connection: LanConnection,
        isOutbound: Boolean = false,
    ) {
        try {
            while (true) {
                val frame = connection.receiveFrame() ?: break
                inboundFrameHandler?.invoke(peerId, frame)
            }
        } finally {
            // Only notify the protocol layer if this connection is still the active one.
            // If registerConnection replaced it with a newer connection, the stale loop
            // must not remove the replacement or signal a spurious peer-disconnect.
            val wasActive = removeConnection(peerId, connection)
            if (wasActive) {
                if (isOutbound) {
                    // Cross-node race: both sides may open simultaneous outbound TCP connections.
                    // Duplicate-connection resolution closes one side's socket, which causes its
                    // receiveLoop to exit.  The inbound replacement may not yet be registered, so
                    // we wait briefly and re-check before signalling a peer-disconnect.
                    delay(300)
                    val replaced = connectionMutex.withLock { connections.containsKey(peerId) }
                    if (!replaced) onPeerDisconnected(peerId)
                } else {
                    onPeerDisconnected(peerId)
                }
            }
        }
    }

    /**
     * Registers [connection] for [peerId], applying duplicate-connection resolution.
     * If both sides connect simultaneously, the connection opened by the peer with the
     * lexicographically smaller nodeId is retained (spec: 6-transport-lan.md).
     *
     * @return true if [connection] was registered; false if it was a duplicate and was closed.
     */
    private suspend fun registerConnection(peerId: String, connection: LanConnection): Boolean {
        connectionMutex.withLock {
            val existing = connections[peerId]
            if (existing != null) {
                if (nodeId < peerId) {
                    // Our nodeId is smaller → keep our existing connection; close the new one.
                    connection.close()
                    return false
                } else {
                    existing.close()
                }
            }
            connections[peerId] = connection
            return true
        }
    }

    /**
     * Removes [connection] from the active-connections map only if it is still the
     * current connection for [peerId].  Returns true if the entry was removed, false if
     * a newer connection had already replaced it (duplicate-connection resolution replaced
     * the old one while its receive-loop was still draining).
     */
    private suspend fun removeConnection(peerId: String, connection: LanConnection): Boolean =
        connectionMutex.withLock {
            if (connections[peerId] === connection) {
                connections.remove(peerId)
                true
            } else {
                false
            }
        }

    companion object {
        const val LINK_MTU = 65_536
        const val MAX_INBOUND_CONNECTIONS = 128
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
    }
}
