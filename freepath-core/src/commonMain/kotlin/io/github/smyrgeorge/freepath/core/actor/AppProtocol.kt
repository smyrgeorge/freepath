package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.TrustLevel
import io.github.smyrgeorge.freepath.model.content.Content
import io.github.smyrgeorge.freepath.model.content.ContentBody
import io.github.smyrgeorge.freepath.model.content.Message

sealed interface AppProtocol : ActorProtocol {
    sealed class Request<R : ActorProtocol.Response> : AppProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    // Generic fire-and-forget ack
    data object Ok : Response()
    data object Pong : Response()

    // Internal
    data object Ping : Request<Pong>()

    // Contact management
    data class AcceptContact(val contact: Contact) : Request<Ok>()
    data class SetTrustLevel(val entry: ContactEntry, val level: TrustLevel) : Request<Ok>()

    // App lifecycle
    data object AppForegrounded : Request<Ok>()
    data object AppBackgrounded : Request<Ok>()

    // Chat
    data class SendMessage(val peerId: String, val text: String) : Request<Ok>()
    data class MessageReceived(val msg: Message) : Request<Ok>()

    // Content
    data class ContentReceived(val envelope: Content) : Request<Ok>()
    data class PublishContent(val body: ContentBody) : Request<Ok>()

    // Network events
    data class PeerConnected(val peerId: String) : Request<Ok>()
    data class PeerIdentified(val peerId: String) : Request<Ok>()

    // Developer / testing
    /** Wipe all contact entries and exit the app — dev/testing only */
    data object ResetData : Request<Ok>()
}