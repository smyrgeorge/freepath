package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.model.contact.ContactCodec
import io.github.smyrgeorge.freepath.model.content.Content
import io.github.smyrgeorge.freepath.model.content.ContentBody
import io.github.smyrgeorge.freepath.model.content.ContentCodec
import io.github.smyrgeorge.freepath.model.content.ContentType
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ContentEntryTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val kp = CryptoProvider.generateEd25519KeyPair()
    private val authorId = ContactCodec.derivePeerId(kp.publicKey)

    private fun article(title: String = "Hello", bodyText: String = "World"): Content =
        ContentCodec.seal(
            body = ContentBody.Article(title = title, body = bodyText),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )

    private fun contactContent(bio: String? = "bio"): Content =
        ContentCodec.seal(
            body = ContentBody.Contact(bio = bio, avatar = null, location = null),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )

    // ── from(content) copies derived fields ─────────────────────────────────────

    @Test
    fun `from copies all the derived fields correctly`() {
        val content = article()
        val entry = ContentEntry.from(content)

        assertEquals(content.id, entry.contentId)
        assertEquals(content.type, entry.type)
        assertEquals(content.authorId, entry.authorId)
        assertEquals(content.version, entry.version)
        assertEquals(content.signature, entry.signature)
        assertEquals(content, entry.content)
        assertEquals(ContentTrust.UNKNOWN, entry.trust)
    }

    @Test
    fun `a fresh entry has no id`() {
        val entry = ContentEntry.from(article())
        assertEquals(0, entry.id)
    }

    @Test
    fun `from with trust VERIFIED sets the trust`() {
        val entry = ContentEntry.from(article(), trust = ContentTrust.VERIFIED)
        assertEquals(ContentTrust.VERIFIED, entry.trust)
    }

    // ── contact() helper ────────────────────────────────────────────────────────

    @Test
    fun `contact returns the ContentBody Contact for a CONTACT-type content`() {
        val content = contactContent(bio = "hello")
        val entry = ContentEntry.from(content)

        assertEquals(ContentType.CONTACT, entry.type)
        val contact = entry.contact()
        assertEquals("hello", contact.bio)
    }

    @Test
    fun `contact throws for an ARTICLE content`() {
        val entry = ContentEntry.from(article())
        assertFails { entry.contact() }
    }
}
