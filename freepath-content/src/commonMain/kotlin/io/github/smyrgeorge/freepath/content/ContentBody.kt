package io.github.smyrgeorge.freepath.content

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed class ContentBody {
    @Serializable
    @SerialName("article")
    data class Article(
        @ProtoNumber(1) val title: String,           // Markdown, max 1024 chars
        @ProtoNumber(2) val body: String,            // Markdown
        @ProtoNumber(3) val author: Author? = null,  // Embedded author info
    ) : ContentBody() {
        init {
            require(title.length <= MAX_TITLE_LENGTH) { "title exceeds maximum length of $MAX_TITLE_LENGTH characters" }
            require(body.length <= MAX_BODY_LENGTH) { "body exceeds maximum length of $MAX_BODY_LENGTH characters" }
        }

        companion object {
            const val MAX_TITLE_LENGTH = 1024
            const val MAX_BODY_LENGTH = 524_288
        }
    }

    @Serializable
    @SerialName("image")
    data class Image(
        @ProtoNumber(1) val data: String,           // Base64-encoded image bytes, max 2 MB decoded
        @ProtoNumber(2) val format: ImageFormat,
        @ProtoNumber(3) val width: Int,             // pixels
        @ProtoNumber(4) val height: Int,            // pixels
        @ProtoNumber(5) val caption: String?,       // Markdown, max 512 chars
        @ProtoNumber(6) val author: Author? = null,  // Embedded author info
    ) : ContentBody() {
        init {
            require(width > 0 && height > 0) { "width and height must be positive" }
            require(caption == null || caption.length <= MAX_CAPTION_LENGTH) { "caption exceeds maximum length of $MAX_CAPTION_LENGTH characters" }
            // Base64 overhead: ceil(2MB / 3) * 4 ≈ 2_796_204 chars
            require(data.length <= MAX_DATA_LENGTH) { "image data exceeds 2 MB" }
        }

        companion object {
            const val MAX_CAPTION_LENGTH = 512
            const val MAX_DATA_LENGTH = 2_796_204
        }
    }

    @Serializable
    @SerialName("contact")
    data class Contact(
        @ProtoNumber(1) val bio: String?,      // Max 256 chars
        @ProtoNumber(2) val avatar: String?,   // Base64-encoded WebP image, max 64 KB
        @ProtoNumber(3) val location: String?, // Max 128 chars
    ) : ContentBody() {
        init {
            require(bio == null || bio.length <= MAX_BIO_LENGTH) { "bio exceeds maximum length of $MAX_BIO_LENGTH characters" }
            require(avatar == null || avatar.length <= MAX_AVATAR_SIZE) { "avatar exceeds maximum size of $MAX_AVATAR_SIZE characters" }
            require(location == null || location.length <= MAX_LOCATION_LENGTH) { "location exceeds maximum length of $MAX_LOCATION_LENGTH characters" }
        }

        companion object {
            const val MAX_BIO_LENGTH = 256
            const val MAX_AVATAR_SIZE = 65536 // 64 KB
            const val MAX_LOCATION_LENGTH = 128
        }
    }
}
