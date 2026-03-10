package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.contact.exchange.ContactExchange
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import kotlinx.coroutines.CompletableDeferred

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

    // Contact exchange — drawer-based flow
    /** Requestor tapped Add on a peer → generate PIN, run [exchanger], initiate exchange */
    class InitiateContactExchange(
        val nodeId: String,
        val exchanger: suspend (pin: String) -> Result<ContactCard>,
    ) : Message<Ok>()

    /** Recipient submitted the PIN they typed in their drawer */
    data class ContactExchangePinSubmitted(val enteredPin: String) : Message<Ok>()

    /** Either side cancelled the exchange (dismissed drawer) */
    data object ContactExchangeCancelled : Message<Ok>()

    /** Recipient: an unknown peer sent an EXCHANGE frame. IO callback suspends on [result]. */
    class IncomingContactExchange(
        val pin: String,
        val peerCardBytes: ByteArray,
        val codec: ContactExchange,
        val result: CompletableDeferred<ByteArray?>,
    ) : Message<Ok>()

    /** Internal: exchange completed — peer card received */
    data class ContactExchangeSucceeded(val peerCard: ContactCard) : Message<Ok>()

    /** Internal: exchange failed */
    data class ContactExchangeFailed(val reason: String) : Message<Ok>()

    // Developer / testing
    /** Wipe all contact entries and exit the app — dev/testing only */
    data object ResetData : Message<Ok>()
}
