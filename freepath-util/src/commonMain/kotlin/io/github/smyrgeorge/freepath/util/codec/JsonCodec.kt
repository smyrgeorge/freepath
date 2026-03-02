package io.github.smyrgeorge.freepath.util.codec

import kotlinx.serialization.json.Json

object JsonCodec {
    val json = Json {
        classDiscriminator = "@type"
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = false
        prettyPrint = false
        coerceInputValues = false
        explicitNulls = false
    }
}