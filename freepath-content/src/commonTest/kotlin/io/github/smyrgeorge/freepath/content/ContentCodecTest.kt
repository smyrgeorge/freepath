package io.github.smyrgeorge.freepath.content

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import io.github.smyrgeorge.freepath.util.codec.JsonCodec
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentCodecTest {

    // ── ContentBody serialization ──────────────────────────────────────────────

    @Test
    fun contentBody_article_serializesWithLowercaseType() {
        val body: ContentBody = ContentBody.Article("My Title", "Body text")
        val json = JsonCodec.json.encodeToString(body)
        assertTrue(json.contains("\"@type\":\"article\""), "Expected @type=article in: $json")
    }

    @Test
    fun contentBody_image_serializesWithLowercaseType() {
        val body: ContentBody = ContentBody.Image(
            data = "aGVsbG8=",
            format = ImageFormat.PNG,
            width = 64,
            height = 64,
        )
        val json = JsonCodec.json.encodeToString(body)
        assertTrue(json.contains("\"@type\":\"image\""), "Expected @type=image in: $json")
    }

    @Test
    fun contentBody_contact_serializesWithLowercaseType() {
        val body: ContentBody = ContentBody.Contact(bio = "Hello")
        val json = JsonCodec.json.encodeToString(body)
        assertTrue(json.contains("\"@type\":\"contact\""), "Expected @type=contact in: $json")
    }

    // ── ContentEnvelope validation ─────────────────────────────────────────────

    @Test
    fun envelope_rejectsTypeMismatch() {
        assertFails {
            ContentEnvelope(
                id = "fakeId",
                type = ContentType.IMAGE,
                authorId = "fakeAuthorId",
                createdAt = 1000L,
                commentsEnabled = false,
                signature = "fakeSig",
                body = ContentBody.Article("title", "body"),  // wrong body type
            )
        }
    }

    @Test
    fun envelope_rejectsCommentsEnabledOnContact() {
        assertFails {
            ContentEnvelope(
                id = "fakeId",
                type = ContentType.CONTACT,
                authorId = "fakeAuthorId",
                createdAt = 1000L,
                commentsEnabled = true,  // must be false for CONTACT
                signature = "fakeSig",
                body = ContentBody.Contact(),
            )
        }
    }

    @Test
    fun envelope_rejectsCommentsEnabledOnPrivateContent() {
        assertFails {
            ContentEnvelope(
                id = "fakeId",
                type = ContentType.ARTICLE,
                authorId = "fakeAuthorId",
                createdAt = 1000L,
                commentsEnabled = true,
                visibility = Visibility.PRIVATE,  // private + commentsEnabled is invalid
                signature = "fakeSig",
                body = ContentBody.Article("title", "text"),
            )
        }
    }

    @Test
    fun envelope_rejectsNullPrevIdForV2() {
        assertFails {
            ContentEnvelope(
                id = "fakeId",
                type = ContentType.ARTICLE,
                authorId = "fakeAuthorId",
                version = 2,
                prevId = null,  // must be non-null for v2+
                createdAt = 1000L,
                commentsEnabled = false,
                signature = "fakeSig",
                body = ContentBody.Article("title", "text"),
            )
        }
    }

    @Test
    fun envelope_rejectsNonNullPrevIdForV1() {
        assertFails {
            ContentEnvelope(
                id = "fakeId",
                type = ContentType.ARTICLE,
                authorId = "fakeAuthorId",
                version = 1,
                prevId = "someId",  // must be null for v1
                createdAt = 1000L,
                commentsEnabled = false,
                signature = "fakeSig",
                body = ContentBody.Article("title", "text"),
            )
        }
    }

    @Test
    fun envelope_rejectsArticleBodyTooLong() {
        assertFails {
            ContentEnvelope(
                id = "fakeId",
                type = ContentType.ARTICLE,
                authorId = "fakeAuthorId",
                createdAt = 1000L,
                commentsEnabled = false,
                signature = "fakeSig",
                body = ContentBody.Article("title", "a".repeat(ContentEnvelope.MAX_ARTICLE_BODY + 1)),
            )
        }
    }

    @Test
    fun envelope_acceptsValidArticle() {
        val env = ContentEnvelope(
            id = "fakeId",
            type = ContentType.ARTICLE,
            authorId = "fakeAuthorId",
            createdAt = 1000L,
            commentsEnabled = true,
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

    // ── deriveId ──────────────────────────────────────────────────────────────

    @Test
    fun deriveId_isDeterministic() {
        val body = articleBody()
        assertEquals(ContentCodec.deriveId(body), ContentCodec.deriveId(body))
    }

    @Test
    fun deriveId_differsForDifferentBodies() {
        val id1 = ContentCodec.deriveId(articleBody("hello"))
        val id2 = ContentCodec.deriveId(articleBody("world"))
        assertTrue(id1 != id2)
    }

    @Test
    fun deriveId_isBase58() {
        val id = ContentCodec.deriveId(articleBody())
        assertTrue(id.all { it in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz" })
        assertTrue(id.isNotEmpty())
    }

    // ── seal / verify ─────────────────────────────────────────────────────────

    @Test
    fun seal_producesVerifiableEnvelope() {
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody(),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
            visibility = Visibility.PUBLIC,
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
            commentsEnabled = true,
            visibility = Visibility.PUBLIC,
        )
        val tampered = env.copy(body = articleBody("tampered"))
        assertFalse(ContentCodec.verify(tampered, kp.publicKey))
    }

    @Test
    fun verify_passesWhenHopsModified() {
        // hops is not signed — modifying it must not break the signature
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody(),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
            visibility = Visibility.PUBLIC,
        )
        val withHops = env.copy(hops = 5)
        assertTrue(ContentCodec.verify(withHops, kp.publicKey))
    }

    // ── seal sets expected fields ─────────────────────────────────────────────

    @Test
    fun seal_setsVersionOneAndNullPrevId() {
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody(),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
            visibility = Visibility.PUBLIC,
        )
        assertEquals(1, env.version)
        assertEquals(null, env.prevId)
    }

    @Test
    fun seal_idMatchesDeriveId() {
        val (kp, authorId) = makeKeys()
        val body = articleBody()
        val env = ContentCodec.seal(
            body = body,
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
            visibility = Visibility.PUBLIC,
        )
        assertEquals(ContentCodec.deriveId(body), env.id)
    }

    // ── edit ──────────────────────────────────────────────────────────────────

    @Test
    fun edit_incrementsVersionAndSetsPrevId() {
        val (kp, authorId) = makeKeys()
        val v1 = ContentCodec.seal(
            body = articleBody("v1"),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
            visibility = Visibility.PUBLIC,
        )
        val v2 = ContentCodec.edit(
            original = v1,
            newBody = articleBody("v2"),
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
        )
        assertEquals(2, v2.version)
        assertEquals(v1.id, v2.prevId)
        assertEquals(v1.createdAt, v2.createdAt)
        assertTrue(v2.id != v1.id)
        assertTrue(ContentCodec.verify(v2, kp.publicKey))
    }

    @Test
    fun edit_preservesExpiresAtByDefault() {
        val (kp, authorId) = makeKeys()
        val v1 = ContentCodec.seal(
            body = articleBody("v1"),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
            visibility = Visibility.PUBLIC,
            expiresAt = 9999L,
        )
        val v2 = ContentCodec.edit(
            original = v1,
            newBody = articleBody("v2"),
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
        )
        assertEquals(9999L, v2.expiresAt)
    }

    // ── encode / decode ───────────────────────────────────────────────────────

    @Test
    fun encodeDecodeRoundTrip() {
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody(),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
            visibility = Visibility.PUBLIC,
        )
        val decoded = ContentCodec.decode(ContentCodec.encode(env)).getOrThrow()
        assertEquals(env, decoded)
    }

    @Test
    fun decode_failsForInvalidJson() {
        val result = ContentCodec.decode("not json".encodeToByteArray())
        assertTrue(result.isFailure)
    }

    @Test
    fun decode_succeedsButVerifyFailsForTamperedSignature() {
        val (kp, authorId) = makeKeys()
        val env = ContentCodec.seal(
            body = articleBody(),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
            commentsEnabled = true,
            visibility = Visibility.PUBLIC,
        )
        // tamper the signature — decode() just deserializes, verify() must catch the bad signature
        val tampered = env.copy(signature = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val result = ContentCodec.decode(ContentCodec.encode(tampered))
        assertTrue(result.isSuccess)
        assertFalse(ContentCodec.verify(result.getOrThrow(), kp.publicKey))
    }
}
