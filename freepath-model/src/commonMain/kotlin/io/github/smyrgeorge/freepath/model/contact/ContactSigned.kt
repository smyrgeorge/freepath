package io.github.smyrgeorge.freepath.model.contact

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class ContactSigned(
    @ProtoNumber(1) val contact: Contact,
    @ProtoNumber(2) val signature: String,
)
