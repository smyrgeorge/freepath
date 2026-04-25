package io.github.smyrgeorge.freepath.model.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
enum class ImageFormat {
    @ProtoNumber(1)
    JPEG,

    @ProtoNumber(2)
    PNG,
}
