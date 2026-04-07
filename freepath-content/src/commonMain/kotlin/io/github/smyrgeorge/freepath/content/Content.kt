package io.github.smyrgeorge.freepath.content

import io.github.smyrgeorge.freepath.util.serializer.InstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Content(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val schema: Int = SCHEMA,
    @ProtoNumber(3) val type: ContentType,
    @ProtoNumber(4) val authorId: String,
    @ProtoNumber(5) val version: Int = 1,
    @ProtoNumber(6) @Serializable(with = InstantSerializer::class) val createdAt: Instant = Clock.System.now(),
    @ProtoNumber(7) @Serializable(with = InstantSerializer::class) val expiresAt: Instant? = null,
    @ProtoNumber(8) val signature: String,
    @ProtoNumber(9) val body: ContentBody,
) {
    init {
        require(schema == SCHEMA) { "Unsupported schema version: $schema (expected $SCHEMA)" }
        require(
            type == ContentType.ARTICLE && body is ContentBody.Article ||
                    type == ContentType.IMAGE && body is ContentBody.Image ||
                    type == ContentType.CONTACT && body is ContentBody.Contact
        ) { "type and body class must match: type=$type body=${body::class.simpleName}" }
        require(version >= 1) { "version must be >= 1" }
    }

    companion object {
        const val SCHEMA = 1
    }
}
