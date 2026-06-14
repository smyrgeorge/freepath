package io.github.smyrgeorge.freepath.core.testing.fake

import io.github.smyrgeorge.freepath.libp2p.AbstractLibp2pModule
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent

/**
 * A fake `Libp2pModule` backed by an in-process [FakeNetwork].
 *
 * It subclasses [AbstractLibp2pModule] so the *real* RPC correlation (`request`), metrics reducer,
 * ping auto-response, inbound `requests` channel and app-handler dispatch are all reused unchanged —
 * only the native I/O is replaced. Inbound events are injected via [inject] (which calls the
 * inherited protected `dispatch`); outbound calls are forwarded to the [network] bus.
 */
class FakeLibp2pModule(private val network: FakeNetwork) : AbstractLibp2pModule() {
    private lateinit var selfPeerId: String

    /** Feed an inbound event into this node, exactly as the native callback would. */
    fun inject(event: Libp2pEvent) = dispatch(event)

    override fun onFirstListenAddr(port: Int) {
        // no-op: the fake network has no listen addresses / mDNS
    }

    override suspend fun start(
        peerId: String,
        sigKeyPrivate: ByteArray,
        listenAddrs: String,
        relayAddrs: String,
        contactLookup: (String) -> Boolean,
    ) {
        selfPeerId = peerId
        network.register(peerId, this)
    }

    override suspend fun stop() {
        if (::selfPeerId.isInitialized) network.unregister(selfPeerId)
    }

    override suspend fun dial(multiaddr: String) {
        // no-op
    }

    override suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray) {
        // Keep-alive pings are pure liveness noise here — connectivity is controlled explicitly via
        // FakeNetwork.connect()/disconnect(), so drop them rather than route them.
        if (reqId == PING_REQ_ID) return
        network.routeRequest(from = selfPeerId, to = peerId, reqId = reqId, payload = payload)
    }

    override suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        network.routeResponse(from = selfPeerId, reqId = reqId, payload = payload, error = null)
    }

    override suspend fun sendResponseFailed(reqId: Long, error: String) {
        network.routeResponse(from = selfPeerId, reqId = reqId, payload = null, error = error)
    }

    companion object {
        // Mirrors AbstractLibp2pModule.PING_REQ_ID (private there). Keep-alive pings use Long.MIN_VALUE.
        private const val PING_REQ_ID: Long = Long.MIN_VALUE
    }
}
