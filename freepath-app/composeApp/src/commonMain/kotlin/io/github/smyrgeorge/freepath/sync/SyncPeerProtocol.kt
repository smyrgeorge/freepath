package io.github.smyrgeorge.freepath.sync

import io.github.smyrgeorge.actor4k.actor.ActorProtocol

sealed interface SyncPeerProtocol : ActorProtocol {
    sealed class Message<R : ActorProtocol.Response> : SyncPeerProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    /** Peer is reachable (libp2p). Flush relay queue only — identity not yet confirmed. */
    data object Connected : Message<Ok>()

    /** Peer identity confirmed. Flush relay queue + sync content. */
    data object Identified : Message<Ok>()
}
