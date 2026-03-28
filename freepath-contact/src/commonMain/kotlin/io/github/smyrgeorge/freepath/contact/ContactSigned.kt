package io.github.smyrgeorge.freepath.contact

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContactSigned(
    @ProtoNumber(1) val contact: Contact,
    @ProtoNumber(2) val signature: String,
)
