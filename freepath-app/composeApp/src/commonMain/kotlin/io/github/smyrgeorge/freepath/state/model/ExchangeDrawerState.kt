package io.github.smyrgeorge.freepath.state.model

import io.github.smyrgeorge.freepath.contact.ContactCard

sealed class ExchangeDrawerState {
    data object Hidden : ExchangeDrawerState()
    data class RequestorWaiting(val pin: String, val peerNodeId: String) : ExchangeDrawerState()
    data class RecipientEnterPin(val peerCard: ContactCard) : ExchangeDrawerState()
    data class Failed(val reason: String) : ExchangeDrawerState()
}
