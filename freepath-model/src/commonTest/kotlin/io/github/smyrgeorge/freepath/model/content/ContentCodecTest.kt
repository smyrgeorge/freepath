package io.github.smyrgeorge.freepath.model.content

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.crypto.KeyPair
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ContentCodecTest {

    // ── ContentEnvelope validation ─────────────────────────────────────────────

    @Test
    fun envelope_rejectsTypeMismatch() {
        assertFails {
            Content(
                id = "fakeId",
                type = ContentType.IMAGE,
                authorId = "fakeAuthorId",
                createdAt = Instant.fromEpochMilliseconds(1000L),
                signature = "fakeSig",
                body = ContentBody.Article("title", "body"),  // wrong body type
            )
        }
    }

    @Test
    fun envelope_rejectsArticleBodyTooLong() {
        assertFails {
            Content(
                id = "fakeId",
                type = ContentType.ARTICLE,
                authorId = "fakeAuthorId",
                createdAt = Instant.fromEpochMilliseconds(1000L),
                signature = "fakeSig",
                body = ContentBody.Article("title", "a".repeat(ContentBody.Article.MAX_BODY_LENGTH + 1)),
            )
        }
    }

    @Test
    fun envelope_acceptsValidArticle() {
        val env = Content(
            id = "fakeId",
            type = ContentType.ARTICLE,
            authorId = "fakeAuthorId",
            createdAt = Instant.fromEpochMilliseconds(1000L),
            signature = "fakeSig",
            body = ContentBody.Article("hello", "world"),
        )
        assertEquals(ContentType.ARTICLE, env.type)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun makeKeys(): Pair<KeyPair, String> {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val authorId = Base64.encode(kp.publicKey)
        return Pair(kp, authorId)
    }

    private fun articleBody(text: String = "hello") = ContentBody.Article("Title $text", text)

    // ── seal / verify ─────────────────────────────────────────────────────────

    @Test
    fun seal_producesVerifiableEnvelope() {
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody(),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )
        assertTrue(ContentCodec.verify(env, kp.publicKey))
    }

    @Test
    fun verify_failsForTamperedBody() {
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody("original"),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )
        val tampered = env.copy(body = articleBody("tampered"))
        assertFalse(ContentCodec.verify(tampered, kp.publicKey))
    }

    // ── seal sets expected fields ─────────────────────────────────────────────

    @Test
    fun seal_setsVersionOne() {
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody(),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )
        assertEquals(1, env.version)
    }

    @Test
    fun seal_idMatchesDeriveId() {
        val (kp, authorId) = makeKeys()
        val body = articleBody()
        val env = ContentCodec.seal(
            body = body,
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )
        assertEquals(ContentBodyCodec.deriveId(body), env.id)
    }

    // ── edit ──────────────────────────────────────────────────────────────────

    @Test
    fun edit_incrementsVersion() {
        val (kp, authorId) = makeKeys()
        val v1 = ContentCodec.seal(
            body = articleBody("v1"),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )
        val v2 = ContentCodec.edit(
            original = v1,
            newBody = articleBody("v2"),
            sigKeyPrivate = kp.privateKey,
        )
        assertEquals(2, v2.version)
        assertEquals(v1.createdAt, v2.createdAt)
        assertTrue(v2.id != v1.id)
        assertTrue(ContentCodec.verify(v2, kp.publicKey))
    }

    @Test
    fun edit_preservesExpiresAtByDefault() {
        val (kp, authorId) = makeKeys()
        val expiry = Instant.fromEpochMilliseconds(9999L)
        val v1 = ContentCodec.seal(
            body = articleBody("v1"),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
            expiresAt = expiry,
        )
        val v2 = ContentCodec.edit(
            original = v1,
            newBody = articleBody("v2"),
            sigKeyPrivate = kp.privateKey,
        )
        assertEquals(expiry, v2.expiresAt)
    }

    // ── encode / decode ───────────────────────────────────────────────────────

    @Test
    fun encodeDecodeRoundTrip() {
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody(),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(env)).getOrThrow()
        assertEquals(
            env.copy(createdAt = Instant.fromEpochMilliseconds(env.createdAt.toEpochMilliseconds())),
            decoded
        )
    }

    @Test
    fun decode_failsForInvalidBytes() {
        val result = ContentCodec.decode("not protobuf".encodeToByteArray())
        assertTrue(result.isFailure)
    }

    @Test
    fun decode_succeedsButVerifyFailsForTamperedSignature() {
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody(),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )
        val tampered = env.copy(signature = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val result = ContentCodec.decode(ContentCodec.encode(tampered))
        assertTrue(result.isSuccess)
        assertFalse(ContentCodec.verify(result.getOrThrow(), kp.publicKey))
    }
}
