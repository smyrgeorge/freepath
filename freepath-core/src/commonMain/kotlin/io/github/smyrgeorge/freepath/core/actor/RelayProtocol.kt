package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.database.RelayEntry

sealed interface RelayProtocol : ActorProtocol {
    sealed class Request<R : ActorProtocol.Response> : RelayProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    /** Carries the number of currently-online peers the queue was distributed to (0 ⇒ still only queued). */
    data class Distributed(val peerCount: Int) : Response()

    /**
     * Persist a relay replica (copies = L) AND immediately distribute it to every peer reachable
     * right now. Used both for a locally-sent message and for an inbound relay packet we receive to
     * carry onward. The reply [Distributed.peerCount] is the number of online peers reached, so the
     * caller can map the outcome to `RELAYED` (≥1) vs `QUEUED` (0); a failed persist surfaces as an
     * ask failure (→ `FAILED`).
     *
     * [fromPeerId] is the peer we received this replica from (null when we are the origin). It is
     * recorded as already-offered, so we never hand the copy back to its source — otherwise the copy
     * budget would halve an extra time on every hop and starve multi-hop delivery.
     */
    data class Distribute(val entry: RelayEntry, val fromPeerId: String? = null) : Request<Distributed>()

    /**
     * Run the full relay pass — Pass 1 direct delivery + Pass 2 binary distribute-and-wait — against a
     * single connected [peerId]. Sent by [PeerActor] when a peer connects / is identified.
     */
    data class RelayToPeer(val peerId: String) : Request<Ok>()

    /** Periodic lifecycle sweep: drop expired / over-aged replicas. Self-sent by the sweep timer. */
    data object Sweep : Request<Ok>()
}
