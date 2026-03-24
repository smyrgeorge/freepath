package io.github.smyrgeorge.freepath.util.codec

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
object ProtobufCodec {
    val protobuf = ProtoBuf {}
}
