package io.github.smyrgeorge.freepath.state.model

sealed class ExchangeDrawerState {
    data object Hidden : ExchangeDrawerState()
    data class RequestorEnterPin(val peripheralId: String) : ExchangeDrawerState()
    data class ResponderWaiting(val pin: String) : ExchangeDrawerState()
    data class Failed(val reason: String) : ExchangeDrawerState()
}
