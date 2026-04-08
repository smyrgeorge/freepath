package io.github.smyrgeorge.freepath.util.codec

import io.github.smyrgeorge.freepath.util.serializer.ByteArrayBase64Serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

object JsonCodec {
    val json = Json {
        classDiscriminator = "@type"
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = false
        prettyPrint = false
        coerceInputValues = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(ByteArray::class, ByteArrayBase64Serializer)
        }
    }
}