package io.github.smyrgeorge.freepath.state.model

import io.github.smyrgeorge.freepath.contact.ContactCard

sealed class ExchangeDrawerState {
    data object Hidden : ExchangeDrawerState()

    /** Shown on requestor's device: display the generated PIN, waiting for peer response */
    data class RequestorWaiting(val pin: String, val peerNodeId: String) : ExchangeDrawerState()

    /** Shown on recipient's device: shows the requestor's card and asks for the PIN */
    data class RecipientEnterPin(val peerCard: ContactCard) : ExchangeDrawerState()

    /** Exchange failed, show error */
    data class Failed(val reason: String) : ExchangeDrawerState()
}
