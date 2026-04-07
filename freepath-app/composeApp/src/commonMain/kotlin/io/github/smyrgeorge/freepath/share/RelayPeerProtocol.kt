package io.github.smyrgeorge.freepath.share

import io.github.smyrgeorge.actor4k.actor.ActorProtocol

sealed interface RelayPeerProtocol : ActorProtocol {
    sealed class Message<R : ActorProtocol.Response> : RelayPeerProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    /** Flush any pending relay packets for this peer. */
    data object Relay : Message<Ok>()
}
