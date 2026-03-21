package io.github.smyrgeorge.freepath.content

import kotlinx.serialization.Serializable

@Serializable
enum class ContentType {
    ARTICLE,
    IMAGE,
    CONTACT,
}
