package io.github.smyrgeorge.freepath.transport.codec

import kotlinx.serialization.json.Json

internal object JsonCodec {
    val json = Json { ignoreUnknownKeys = true }
}
