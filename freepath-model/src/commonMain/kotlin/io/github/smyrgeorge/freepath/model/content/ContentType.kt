package io.github.smyrgeorge.freepath.model.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
enum class ContentType {
    @ProtoNumber(1)
    ARTICLE,

    @ProtoNumber(2)
    IMAGE,

    @ProtoNumber(3)
    CONTACT,
}
