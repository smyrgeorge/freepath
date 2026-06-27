package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.ActorProtocol

sealed interface PeerProtocol : ActorProtocol {
    sealed class Request<R : ActorProtocol.Response> : PeerProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    /** Peer is reachable (libp2p). Flush relay queue only — identity not yet confirmed. */
    data object Connected : Request<Ok>()

    /** Peer identity confirmed. Flush relay queue + sync content. */
    data object Identified : Request<Ok>()
}
