package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.ActorProtocol

sealed interface PeerProtocol : ActorProtocol {
    sealed class Request<R : ActorProtocol.Response> : PeerProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    /** Peer is reachable (libp2p), identity not yet confirmed. Sync stored content (full feed pass). */
    data object Connected : Request<Ok>()

    /** Peer identity confirmed (known contact). Flush relay queue + push our contact card. */
    data object Identified : Request<Ok>()
}
