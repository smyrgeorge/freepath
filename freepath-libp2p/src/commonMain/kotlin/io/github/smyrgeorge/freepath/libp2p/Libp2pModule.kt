package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.freepath.libp2p.metrics.Libp2pMetrics
import io.github.smyrgeorge.freepath.libp2p.util.AbstractLibp2pModule

/**
 * Wraps a libp2p swarm. Minimal API: start/stop + event handler.
 *
 * Usage:
 * ```kotlin
 * val module = LibP2pModule()
 * module.setEventHandler { event -> /* handle LibP2pEvent */ }
 * module.start(nodeId = "Qm...", sigKeyPrivate = myKey)
 * // ... later ...
 * module.stop()
 * ```
 */
expect class Libp2pModule() : AbstractLibp2pModule {
    val metrics: Libp2pMetrics

    /** Register a handler invoked on each swarm event. Call before start(). */
    fun setEventHandler(handler: (Libp2pEvent) -> Unit)

    /**
     * Start the libp2p node. Idempotent — subsequent calls are no-ops.
     * @param nodeId freepath nodeId (Base58 string) — from `AbstractAppState.contactCard.nodeId`.
     * @param sigKeyPrivate 32-byte Ed25519 private key seed — from `AbstractAppState.identity.sigKeyPrivate`.
     * @param listenAddrs Newline-separated Multiaddr strings; each address is passed to listen_on.
     *   Defaults to TCP on any available port for both IPv4 and IPv6.
     * @throws Libp2pException if startup fails.
     */
    suspend fun start(
        nodeId: String,
        sigKeyPrivate: ByteArray,
        listenAddrs: String = defaultListenAddrs,
    )

    /** Stop the node and release all resources. Idempotent. */
    suspend fun stop()

    /** Dial a peer by multiaddr string (e.g. "/ip4/192.168.1.5/tcp/12345"). No-op if not started. */
    suspend fun dial(multiaddr: String)

    /**
     * Send an RPC request to a connected peer and expect a response.
     * The caller assigns [reqId] as a correlation ID; the swarm will deliver a
     * [Libp2pEvent.ResponseReceived] or [Libp2pEvent.RequestFailed] event with the same [reqId].
     * @param peerId libp2p PeerId string.
     * @param reqId caller-assigned correlation ID (must be unique per in-flight request).
     * @param payload request payload bytes.
     * No-op if the node is not started.
     */
    override suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray)

    /**
     * Send a successful response to an incoming [Libp2pEvent.RequestReceived].
     * @param reqId the [Libp2pEvent.RequestReceived.reqId] to respond to.
     * @param payload response payload bytes.
     * No-op if the node is not started.
     */
    suspend fun sendResponse(reqId: Long, payload: ByteArray)

    /**
     * Send an error response to an incoming [Libp2pEvent.RequestReceived].
     * The original sender receives a [Libp2pEvent.RequestFailed] event with [error] as the description.
     * @param reqId the [Libp2pEvent.RequestReceived.reqId] to respond to.
     * @param error human-readable error description.
     * No-op if the node is not started.
     */
    suspend fun sendResponseFailed(reqId: Long, error: String)
}
