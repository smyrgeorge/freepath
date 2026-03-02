package io.github.smyrgeorge.freepath.contact

import kotlinx.serialization.Serializable

@Serializable
data class ContactCardSigned(
    val card: ContactCard,
    val signature: String,
)
