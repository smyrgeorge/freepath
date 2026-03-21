package io.github.smyrgeorge.freepath.content

import io.github.smyrgeorge.freepath.content.ContentCodec.verify
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.codec.Base58
import io.github.smyrgeorge.freepath.util.codec.JsonCodec
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock

object ContentCodec {

    const val SCHEMA = 1

    // ── Canonical signable envelope (excludes hops and signature) ─────────────

    // Fields excluded from signing: `hops` (incremented by carriers) and `signature` (the signature itself).
    @Serializable
    private data class Signable(
        val id: String,
        val schema: Int,
        val type: ContentType,
        val authorId: String,
        val version: Int,
        val prevId: String?,
        val createdAt: Long,
        val expiresAt: Long?,
        val commentsEnabled: Boolean,
        val visibility: Visibility,
        val body: ContentBody,
    )

    private fun toSignable(envelope: ContentEnvelope): Signable = Signable(
        id = envelope.id,
        schema = envelope.schema,
        type = envelope.type,
        authorId = envelope.authorId,
        version = envelope.version,
        prevId = envelope.prevId,
        createdAt = envelope.createdAt,
        expiresAt = envelope.expiresAt,
        commentsEnabled = envelope.commentsEnabled,
        visibility = envelope.visibility,
        body = envelope.body,
    )

    // ── ID derivation ─────────────────────────────────────────────────────────

    /** Returns Base58( SHA-256( canonical body JSON ) ). Deterministic across devices. */
    fun deriveId(body: ContentBody): String {
        val bytes = JsonCodec.json.encodeToString(body).encodeToByteArray()
        return Base58.encode(CryptoProvider.sha256(bytes))
    }

    // ── Signing ───────────────────────────────────────────────────────────────

    /** Returns a 64-byte Ed25519 signature over the canonical envelope (hops and signature excluded). */
    fun sign(envelope: ContentEnvelope, sigKeyPrivate: ByteArray): ByteArray {
        val bytes = JsonCodec.json.encodeToString(toSignable(envelope)).encodeToByteArray()
        return CryptoProvider.ed25519Sign(sigKeyPrivate, bytes)
    }

    /** Returns true if the envelope signature is valid for the given public key. */
    fun verify(envelope: ContentEnvelope, sigKeyPublic: ByteArray): Boolean {
        val signatureBytes = Base64.decode(envelope.signature)
        val bytes = JsonCodec.json.encodeToString(toSignable(envelope)).encodeToByteArray()
        return CryptoProvider.ed25519Verify(sigKeyPublic, bytes, signatureBytes)
    }

    // ── seal / edit ───────────────────────────────────────────────────────────

    fun seal(
        body: ContentBody,
        authorId: String,
        sigKeyPrivate: ByteArray,
        commentsEnabled: Boolean,
        visibility: Visibility = Visibility.PUBLIC,
        expiresAt: Long? = null,
    ): ContentEnvelope {
        val type = typeOf(body)
        val id = deriveId(body)
        val createdAt = Clock.System.now().toEpochMilliseconds()
        val placeholder = ContentEnvelope(
            id = id,
            schema = SCHEMA,
            type = type,
            authorId = authorId,
            version = 1,
            prevId = null,
            createdAt = createdAt,
            expiresAt = expiresAt,
            commentsEnabled = commentsEnabled,
            visibility = visibility,
            hops = 0,
            signature = "",
            body = body,
        )
        val signature = Base64.encode(sign(placeholder, sigKeyPrivate))
        return placeholder.copy(signature = signature)
    }

    /**
     * Creates a new, signed version of [original] with [newBody].
     * Increments [ContentEnvelope.version], sets [ContentEnvelope.prevId], preserves [ContentEnvelope.createdAt].
     * [commentsEnabled] must be passed explicitly — it may change between versions.
     * [expiresAt] defaults to carrying forward the original value.
     */
    fun edit(
        original: ContentEnvelope,
        newBody: ContentBody,
        sigKeyPrivate: ByteArray,
        commentsEnabled: Boolean,
        expiresAt: Long? = original.expiresAt,
    ): ContentEnvelope {
        val type = typeOf(newBody)
        val id = deriveId(newBody)
        val placeholder = ContentEnvelope(
            id = id,
            schema = original.schema,
            type = type,
            authorId = original.authorId,
            version = original.version + 1,
            prevId = original.id,
            createdAt = original.createdAt,
            expiresAt = expiresAt,
            commentsEnabled = commentsEnabled,
            visibility = original.visibility,
            hops = 0,
            signature = "",
            body = newBody,
        )
        val signature = Base64.encode(sign(placeholder, sigKeyPrivate))
        return placeholder.copy(signature = signature)
    }

    // ── JSON encode/decode ────────────────────────────────────────────────────

    /** Encodes [envelope] to UTF-8 JSON bytes. */
    fun encode(envelope: ContentEnvelope): ByteArray =
        JsonCodec.json.encodeToString(envelope).encodeToByteArray()

    /**
     * Decodes a [ContentEnvelope] from UTF-8 JSON bytes.
     * Returns [Result.failure] on parse error.
     * Signature verification is NOT performed here — call [verify] separately with the author's public key.
     */
    fun decode(data: ByteArray): Result<ContentEnvelope> = runCatching {
        JsonCodec.json.decodeFromString<ContentEnvelope>(data.decodeToString())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun typeOf(body: ContentBody): ContentType = when (body) {
        is ContentBody.Article -> ContentType.ARTICLE
        is ContentBody.Image -> ContentType.IMAGE
        is ContentBody.Contact -> ContentType.CONTACT
    }
}
