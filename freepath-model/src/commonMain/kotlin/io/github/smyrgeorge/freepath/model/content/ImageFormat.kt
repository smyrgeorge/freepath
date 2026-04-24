package io.github.smyrgeorge.freepath.model.content

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class ImageFormat {
    @ProtoNumber(1)
    JPEG,

    @ProtoNumber(2)
    PNG,
}
