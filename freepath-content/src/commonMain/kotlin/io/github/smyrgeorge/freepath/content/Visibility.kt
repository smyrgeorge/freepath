package io.github.smyrgeorge.freepath.content

import kotlinx.serialization.Serializable

@Serializable
enum class Visibility {
    PUBLIC,
    PRIVATE,
    // ACCESS_CONTROLLED deferred until hubs spec
}
