package io.github.smyrgeorge.freepath.contact

import kotlinx.serialization.Serializable

@Serializable
data class SignedContactCard(
    val card: ContactCard,
    val signature: String,
)
