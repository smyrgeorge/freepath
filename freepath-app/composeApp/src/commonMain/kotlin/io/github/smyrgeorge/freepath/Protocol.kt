package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.libnet.client.model.ChatMessage
import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.content.ContentBody
import io.github.smyrgeorge.freepath.database.ContactEntry

sealed interface Protocol : ActorProtocol {
    sealed class Message<R : ActorProtocol.Response> : Protocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    // Generic fire-and-forget ack
    data object Ok : Response()

    // Lifecycle
    data object Ping : Message<Pong>()
    data object Pong : Response()

    // Contact management
    data class AcceptContact(val contact: Contact) : Message<Ok>()
    data class SetTrustLevel(val entry: ContactEntry, val level: TrustLevel) : Message<Ok>()

    // App lifecycle
    data object AppForegrounded : Message<Ok>()
    data object AppBackgrounded : Message<Ok>()

    // Contact exchange — BLE drawer-based flow
    data class BleInitiateContactExchange(val peripheralId: String) : Message<Ok>()
    data class BleBeginInitiatorContactExchange(val peripheralId: String, val pin: String) : Message<Ok>()
    data object BleInitiateResponderContactExchange : Message<Ok>()
    data object BleContactExchangeCancelled : Message<Ok>()
    data class BleContactExchangeSucceeded(val contact: Contact, val peripheralId: String) : Message<Ok>()
    data class BleContactExchangeFailed(val reason: String) : Message<Ok>()

    // Chat
    data class SendChatMessage(val peerId: String, val text: String) : Message<Ok>()
    data class ChatMessageReceived(val senderId: String, val receiverId: String, val msg: ChatMessage) : Message<Ok>()

    // Content
    data class ContentReceived(val envelope: Content) : Message<Ok>()
    data class PublishContent(val body: ContentBody) : Message<Ok>()

    // Network events
    data class PeerIdentified(val peerId: String) : Message<Ok>()

    // Developer / testing
    /** Wipe all contact entries and exit the app — dev/testing only */
    data object ResetData : Message<Ok>()
}
