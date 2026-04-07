package io.github.smyrgeorge.freepath.content

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class ContentType {
    @ProtoNumber(1)
    ARTICLE,

    @ProtoNumber(2)
    IMAGE,

    @ProtoNumber(3)
    CONTACT,
}
