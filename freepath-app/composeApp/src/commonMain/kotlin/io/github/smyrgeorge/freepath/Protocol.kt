package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.client.model.ChatMessage
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.database.ContactCardEntry

sealed interface Protocol : ActorProtocol {
    sealed class Message<R : ActorProtocol.Response> : Protocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    // Generic fire-and-forget ack
    data object Ok : Response()

    // Lifecycle
    data object Ping : Message<Pong>()
    data object Pong : Response()

    // Contact management
    data class AcceptContact(val card: ContactCard) : Message<Ok>()
    data class SetTrustLevel(val entry: ContactCardEntry, val level: TrustLevel) : Message<Ok>()

    // App lifecycle
    data object AppForegrounded : Message<Ok>()
    data object AppBackgrounded : Message<Ok>()

    // Contact exchange — drawer-based flow
    data class InitiateContactExchange(val peerId: String) : Message<Ok>()
    data class ContactExchangePinSubmitted(val enteredPin: String) : Message<Ok>()
    data object ContactExchangeCancelled : Message<Ok>()
    data class IncomingContactExchange(
        val peerId: String,
        val pin: String,
        val peerCard: ContactCard,
    ) : Message<Ok>()

    data class ContactExchangeSucceeded(val peerCard: ContactCard) : Message<Ok>()
    data class ContactExchangeFailed(val reason: String) : Message<Ok>()

    // Chat
    data class SendChatMessage(val peerId: String, val text: String) : Message<Ok>()
    data class ChatMessageReceived(
        val senderId: String,
        val receiverId: String,
        val message: ChatMessage
    ) : Message<Ok>()

    // Developer / testing
    /** Wipe all contact entries and exit the app — dev/testing only */
    data object ResetData : Message<Ok>()
}
