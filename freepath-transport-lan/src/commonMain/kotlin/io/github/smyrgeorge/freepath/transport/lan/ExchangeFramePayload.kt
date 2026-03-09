package io.github.smyrgeorge.freepath.transport.lan

import kotlinx.serialization.Serializable

internal object ExchangeFramePayload {
    @Serializable
    data class Request(val pin: String, val card: String)

    @Serializable
    data class Response(val ok: Boolean, val card: String? = null)
}
