package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.model.content.Content

sealed interface PeerProtocol : ActorProtocol {
    sealed class Request<R : ActorProtocol.Response> : PeerProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    /** Peer is reachable (libp2p), identity not yet confirmed. Sync stored content (full feed pass). */
    data object Connected : Request<Ok>()

    /** Peer identity confirmed (known contact). Flush relay queue + push our contact card. */
    data object Identified : Request<Ok>()

    /**
     * Our own contact card changed (today: the avatar) — push the fresh [content] to this peer now,
     * instead of waiting for their next reconnect. Carries the content so it never uses a stale snapshot.
     */
    data class SyncContact(val content: Content) : Request<Ok>()
}
