package io.github.smyrgeorge.freepath.content

import kotlinx.serialization.Serializable

@Serializable
data class ContentEnvelope(
    val id: String,
    val schema: Int = 1,
    val type: ContentType,
    val authorId: String,
    val version: Int = 1,
    val prevId: String? = null,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val commentsEnabled: Boolean,
    val visibility: Visibility = Visibility.PUBLIC,
    val hops: Int = 0,
    val signature: String,
    val body: ContentBody,
) {
    init {
        require(schema == SCHEMA) { "Unsupported schema version: $schema (expected $SCHEMA)" }

        require(
            type == ContentType.ARTICLE && body is ContentBody.Article ||
                    type == ContentType.IMAGE && body is ContentBody.Image ||
                    type == ContentType.CONTACT && body is ContentBody.Contact
        ) { "type and body class must match: type=$type body=${body::class.simpleName}" }

        require(!(type == ContentType.CONTACT && commentsEnabled)) { "commentsEnabled must be false for CONTACT" }
        // Note: when ACCESS_CONTROLLED visibility is added, it must also require commentsEnabled=false.
        require(!(visibility == Visibility.PRIVATE && commentsEnabled)) { "commentsEnabled must be false for PRIVATE content" }

        require(version >= 1) { "version must be >= 1" }
        require((version == 1) == (prevId == null)) { "prevId must be null for v1 and non-null for v2+" }

        when (body) {
            is ContentBody.Article -> {
                require(body.title.length <= MAX_ARTICLE_TITLE)
                require(body.body.length <= MAX_ARTICLE_BODY)
            }

            is ContentBody.Image -> {
                require(body.width in 1..MAX_IMAGE_DIM && body.height in 1..MAX_IMAGE_DIM)
                require(body.caption == null || body.caption.length <= MAX_DESCRIPTION)
                // Base64 overhead: ceil(2MB / 3) * 4 ≈ 2_796_204 chars
                require(body.data.length <= MAX_IMAGE_BASE64) { "image data exceeds 2 MB" }
            }

            is ContentBody.Contact -> {
                require(body.bio == null || body.bio.length <= ContentBody.Contact.MAX_BIO_LENGTH)
                require(body.avatar == null || body.avatar.length <= ContentBody.Contact.MAX_AVATAR_SIZE)
                require(body.location == null || body.location.length <= ContentBody.Contact.MAX_LOCATION_LENGTH)
            }
        }
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_ARTICLE_TITLE = 128
        const val MAX_DESCRIPTION = 512
        const val MAX_IMAGE_DIM = 2048
        const val MAX_IMAGE_BASE64 = 2_796_204
        const val MAX_ARTICLE_BODY = 524_288
    }
}
