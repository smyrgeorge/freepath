package io.github.smyrgeorge.freepath.model.content

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.codec.Base58
import io.github.smyrgeorge.freepath.util.codec.ProtobufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray

@OptIn(ExperimentalSerializationApi::class)
object ContentBodyCodec {
    fun deriveId(body: ContentBody): String {
        val bytes = ProtobufCodec.protobuf.encodeToByteArray(body)
        return Base58.encode(CryptoProvider.sha256(bytes))
    }
}
