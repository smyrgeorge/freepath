package io.github.smyrgeorge.freepath.state.model

import kotlin.time.Instant

data class ChatMessage(
    val fromMe: Boolean,
    val text: String,
    val timestamp: Instant,
)
