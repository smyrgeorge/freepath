package io.github.smyrgeorge.freepath.share

import io.github.smyrgeorge.actor4k.actor.ActorProtocol

sealed interface PeerProtocol : ActorProtocol {
    sealed class Message<R : ActorProtocol.Response> : PeerProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    /** Trigger a content sync to this peer. */
    data object Sync : Message<Ok>()
}
