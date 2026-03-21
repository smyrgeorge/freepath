package io.github.smyrgeorge.freepath.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ContentBody {

    @Serializable
    @SerialName("article")
    data class Article(
        val title: String,          // Markdown, max 128 chars
        val body: String,           // Markdown
    ) : ContentBody()

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,           // Base64-encoded image bytes, max 2 MB decoded
        val format: ImageFormat,
        val width: Int,             // pixels, max 2048
        val height: Int,            // pixels, max 2048
        val caption: String? = null, // Markdown, max 512 chars
    ) : ContentBody()

    @Serializable
    @SerialName("contact")
    data class Contact(
        val bio: String? = null,        // Max 256 chars
        val avatar: String? = null,     // Base64-encoded WebP image, max 64 KB
        val location: String? = null,   // Max 128 chars
    ) : ContentBody() {
        companion object {
            const val MAX_BIO_LENGTH = 256
            const val MAX_AVATAR_SIZE = 65536 // 64 KB
            const val MAX_LOCATION_LENGTH = 128
        }
    }
}
