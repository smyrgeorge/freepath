package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.database.ContactCardEntry

sealed interface Protocol : ActorProtocol {
    sealed class Message<R : ActorProtocol.Response> : Protocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    // Generic fire-and-forget ack
    data object Ok : Response()

    // Lifecycle (existing)
    data object Ping : Message<Pong>()
    data object Pong : Response()

    // Contact management
    data class AcceptContact(val card: ContactCard) : Message<Ok>()
    data class SetTrustLevel(val entry: ContactCardEntry, val level: TrustLevel) : Message<Ok>()

    // Peer lifecycle (from LAN adapter callbacks)
    data class PeerDiscovered(val nodeId: String) : Message<Ok>()
    data class PeerLost(val nodeId: String) : Message<Ok>()
    data class PeerConnected(val nodeId: String) : Message<Ok>()
    data class PeerDisconnected(val nodeId: String) : Message<Ok>()

    // App lifecycle
    data object AppForegrounded : Message<Ok>()
    data object AppBackgrounded : Message<Ok>()
}
