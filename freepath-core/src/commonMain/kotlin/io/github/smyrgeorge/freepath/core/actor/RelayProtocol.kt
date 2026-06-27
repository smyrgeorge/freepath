package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.database.RelayEntry

sealed interface RelayProtocol : ActorProtocol {
    sealed class Request<R : ActorProtocol.Response> : RelayProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    /** Carries the persisted master replica back to the caller (for its id / logging). */
    data class Stored(val entry: RelayEntry) : Response()

    /** Carries the number of currently-online peers the queue was distributed to (0 ⇒ still only queued). */
    data class Distributed(val peerCount: Int) : Response()

    /** Persist a freshly-sealed master replica (copies = L). Reply once it is durably committed. */
    data class Enqueue(val entry: RelayEntry) : Request<Stored>()

    /**
     * Run the full relay pass — Pass 1 direct delivery + Pass 2 binary distribute-and-wait — against a
     * single connected [peerId]. Sent by [PeerActor] when a peer connects / is identified.
     */
    data class RelayToPeer(val peerId: String) : Request<Ok>()

    /**
     * Distribute the relay queue to every peer reachable right now (used when a message is freshly
     * queued). The reply [Distributed.peerCount] is the number of online peers reached, so the caller
     * can map the outcome to `RELAYED` (≥1) vs `QUEUED` (0).
     */
    data object Distribute : Request<Distributed>()

    /** Periodic lifecycle sweep: drop expired / over-aged replicas. Self-sent by the sweep timer. */
    data object Sweep : Request<Ok>()
}
