package io.github.smyrgeorge.freepath.content

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.codec.ProtobufCodec
import io.github.smyrgeorge.freepath.util.serializer.InstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.io.encoding.Base64
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
object ContentCodec {

    const val SCHEMA = 1

    fun sign(envelope: Content, sigKeyPrivate: ByteArray): ByteArray {
        val bytes = ProtobufCodec.protobuf.encodeToByteArray(toSignable(envelope))
        return CryptoProvider.ed25519Sign(sigKeyPrivate, bytes)
    }

    fun verify(envelope: Content, sigKeyPublic: String): Boolean {
        val sigKeyPublic = Base64.decode(sigKeyPublic)
        return verify(envelope, sigKeyPublic)
    }

    fun verify(envelope: Content, sigKeyPublic: ByteArray): Boolean {
        val signatureBytes = Base64.decode(envelope.signature)
        val bytes = ProtobufCodec.protobuf.encodeToByteArray(toSignable(envelope))
        return CryptoProvider.ed25519Verify(sigKeyPublic, bytes, signatureBytes)
    }

    fun seal(
        body: ContentBody,
        authorId: String,
        sigKeyPrivate: ByteArray,
        expiresAt: Instant? = null,
    ): Content = seal(
        id = ContentBodyCodec.deriveId(body),
        body = body,
        authorId = authorId,
        sigKeyPrivate = sigKeyPrivate,
        expiresAt = expiresAt,
    )

    fun seal(
        id: String,
        body: ContentBody,
        authorId: String,
        sigKeyPrivate: ByteArray,
        expiresAt: Instant? = null,
    ): Content {
        val placeholder = Content(
            id = id,
            schema = SCHEMA,
            type = typeOf(body),
            authorId = authorId,
            version = 1,
            expiresAt = expiresAt,
            signature = "",
            body = body,
        )
        val signature = Base64.encode(sign(placeholder, sigKeyPrivate))
        return placeholder.copy(signature = signature)
    }

    /**
     * Creates a new, signed version of [original] with [newBody].
     * Increments [Content.version], preserves [Content.createdAt].
     * [expiresAt] defaults to carrying forward the original value.
     */
    fun edit(
        original: Content,
        newBody: ContentBody,
        sigKeyPrivate: ByteArray,
        expiresAt: Instant? = original.expiresAt,
    ): Content {
        val type = typeOf(newBody)
        val id = ContentBodyCodec.deriveId(newBody)
        val placeholder = Content(
            id = id,
            schema = original.schema,
            type = type,
            authorId = original.authorId,
            version = original.version + 1,
            createdAt = original.createdAt,
            expiresAt = expiresAt,
            signature = "",
            body = newBody,
        )
        val signature = Base64.encode(sign(placeholder, sigKeyPrivate))
        return placeholder.copy(signature = signature)
    }

    fun encode(envelope: Content): ByteArray = ProtobufCodec.protobuf.encodeToByteArray(envelope)
    fun decode(data: ByteArray): Result<Content> = runCatching {
        ProtobufCodec.protobuf.decodeFromByteArray<Content>(data)
    }

    private fun typeOf(body: ContentBody): ContentType = when (body) {
        is ContentBody.Article -> ContentType.ARTICLE
        is ContentBody.Image -> ContentType.IMAGE
        is ContentBody.Contact -> ContentType.CONTACT
    }

    private fun toSignable(envelope: Content): Signable = Signable(
        id = envelope.id,
        schema = envelope.schema,
        type = envelope.type,
        authorId = envelope.authorId,
        version = envelope.version,
        createdAt = envelope.createdAt,
        expiresAt = envelope.expiresAt,
        body = envelope.body,
    )

    @Serializable
    private data class Signable(
        @ProtoNumber(1) val id: String,
        @ProtoNumber(2) val schema: Int,
        @ProtoNumber(3) val type: ContentType,
        @ProtoNumber(4) val authorId: String,
        @ProtoNumber(5) val version: Int,
        @ProtoNumber(6) @Serializable(with = InstantSerializer::class) val createdAt: Instant,
        @ProtoNumber(7) @Serializable(with = InstantSerializer::class) val expiresAt: Instant?,
        @ProtoNumber(8) val body: ContentBody,
    )
}
