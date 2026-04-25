package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.model.contact.Contact

sealed interface ContactExchangeProtocol : ActorProtocol {
    sealed class Request<R : ActorProtocol.Response> : ContactExchangeProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    // Lifecycle
    data object Ping : Request<Pong>()
    data object Pong : Response()

    /** UI scanned a peripheral and is ready to enter the responder's PIN. */
    data class Initiate(val peripheralId: String) : Request<Ok>()

    /** UI started responder mode; show generated PIN and await the initiator. */
    data object InitiateResponder : Request<Ok>()

    /** Initiator entered the PIN and the BLE exchange should begin. */
    data class BeginInitiator(val peripheralId: String, val pin: String) : Request<Ok>()

    /** User dismissed the drawer (also handles the Failed-drawer Dismiss). */
    data object Cancelled : Request<Ok>()

    /** BLE exchange completed; persist the contact + identity secret. */
    data class Succeeded(
        val contact: Contact,
        val peripheralId: String,
        val identitySecret: ByteArray,
    ) : Request<Ok>() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Succeeded

            if (contact != other.contact) return false
            if (peripheralId != other.peripheralId) return false

            return true
        }

        override fun hashCode(): Int {
            var result = contact.hashCode()
            result = 31 * result + peripheralId.hashCode()
            return result
        }
    }

    /** BLE exchange failed; show Failed drawer until user dismisses. */
    data class Failed(val reason: String) : Request<Ok>()
}
