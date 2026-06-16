package io.github.smyrgeorge.freepath.core.testing.fake

import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process replacement for the physical p2p network. Holds the connectivity graph between nodes
 * and routes request/response traffic between their [FakeLibp2pModule]s.
 *
 * Connectivity is expressed the same way the real stack learns it: by injecting
 * `PeerConnected`/`PeerIdentified`/`PeerDisconnected` events into each node, which drive the real
 * metrics (and hence `LibnetModule` routing) and the real app event handler (and hence the
 * `AppActor` → `PeerActor` sync/relay path).
 *
 * Request/response correlation: each outbound request is assigned a globally-unique *wire* reqId for
 * the receiver side, and the response is mapped back to the requester's original reqId. This mirrors
 * how a real per-stream transport scopes correlation, and sidesteps the cross-node reqId collisions
 * that would otherwise happen (every node's RPC counter starts at 0).
 *
 * Thread-safety: every entry point holds [mutex]. The `inject(...)` calls made while holding it only
 * do non-blocking channel sends + coroutine launches, so there is no risk of blocking under the lock.
 */
class FakeNetwork {
    private val mutex = Mutex()
    private val fakes = HashMap<String, FakeLibp2pModule>()   // peerId -> node transport
    private val links = HashSet<Set<String>>()               // {peerIdA, peerIdB} present == connected
    private val pending = HashMap<Long, Pending>()           // wire reqId -> requester
    private var nextWire: Long = 1L                          // unique, positive, never PING/MIN_VALUE

    internal suspend fun register(peerId: String, fake: FakeLibp2pModule) = mutex.withLock {
        fakes[peerId] = fake
    }

    internal suspend fun unregister(peerId: String): Unit = mutex.withLock {
        fakes.remove(peerId)
        links.removeAll { peerId in it }
        pending.values.removeAll { it.requesterPeerId == peerId }
    }

    /** Connect two peers (mutually): drives `PeerConnected` + `PeerIdentified` into both. */
    suspend fun connect(peerIdA: String, peerIdB: String): Unit = mutex.withLock {
        links.add(setOf(peerIdA, peerIdB))
        fakes[peerIdA]?.let {
            it.inject(Libp2pEvent.PeerConnected(peerIdB, "/fake/p2p/$peerIdB"))
            it.inject(Libp2pEvent.PeerIdentified(peerIdB))
        }
        fakes[peerIdB]?.let {
            it.inject(Libp2pEvent.PeerConnected(peerIdA, "/fake/p2p/$peerIdA"))
            it.inject(Libp2pEvent.PeerIdentified(peerIdA))
        }
    }

    /** Disconnect two peers (mutually): drives `PeerDisconnected` into both. */
    suspend fun disconnect(peerIdA: String, peerIdB: String): Unit = mutex.withLock {
        links.remove(setOf(peerIdA, peerIdB))
        fakes[peerIdA]?.inject(Libp2pEvent.PeerDisconnected(peerIdB))
        fakes[peerIdB]?.inject(Libp2pEvent.PeerDisconnected(peerIdA))
    }

    /** True if the two peers are currently connected. */
    suspend fun isConnected(peerIdA: String, peerIdB: String): Boolean = mutex.withLock {
        setOf(peerIdA, peerIdB) in links
    }

    internal suspend fun routeRequest(from: String, to: String, reqId: Long, payload: ByteArray): Unit =
        mutex.withLock {
            val target = fakes[to]
            if (target == null || setOf(from, to) !in links) {
                // Fail fast so the sender's request() resolves with an error instead of timing out.
                fakes[from]?.inject(
                    Libp2pEvent.RequestFailed(
                        reqId,
                        senderId = to,
                        recipientId = from,
                        error = "peer $to is not reachable"
                    )
                )
                return@withLock
            }
            val wire = nextWire++
            pending[wire] = Pending(requesterPeerId = from, requesterReqId = reqId)
            target.inject(Libp2pEvent.RequestReceived(wire, senderId = from, recipientId = to, payload = payload))
        }

    internal suspend fun routeResponse(from: String, reqId: Long, payload: ByteArray?, error: String?): Unit =
        mutex.withLock {
            val p = pending.remove(reqId) ?: return@withLock
            val requester = fakes[p.requesterPeerId] ?: return@withLock
            if (error != null) {
                requester.inject(
                    Libp2pEvent.RequestFailed(
                        p.requesterReqId,
                        senderId = from,
                        recipientId = p.requesterPeerId,
                        error = error
                    )
                )
            } else {
                requester.inject(
                    Libp2pEvent.ResponseReceived(
                        p.requesterReqId,
                        senderId = from,
                        recipientId = p.requesterPeerId,
                        payload = payload ?: ByteArray(0),
                    )
                )
            }
        }

    private data class Pending(val requesterPeerId: String, val requesterReqId: Long)
}
