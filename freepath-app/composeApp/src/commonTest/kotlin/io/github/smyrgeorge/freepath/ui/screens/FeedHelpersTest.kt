package io.github.smyrgeorge.freepath.ui.screens

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.content.ContentBody
import io.github.smyrgeorge.freepath.content.ImageFormat
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class FeedHelpersTest {

    // ContactCardEntry.init enforces nodeId must be 52-character Base58
    private val NODE_A = "Qm11111111111111111111111111111111111111111111111111"  // 52 chars
    private val NODE_B = "Qm22222222222222222222222222222222222222222222222222"
    private val NODE_C = "Qm33333333333333333333333333333333333333333333333333"
    private val NODE_X = "Qm44444444444444444444444444444444444444444444444444"

    private fun fakeEntry(nodeId: String, trust: TrustLevel): ContactCardEntry =
        ContactCardEntry(
            nodeId = nodeId,
            card = ContactCard(
                schema = 1,
                sigKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                encKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                updatedAt = Clock.System.now(),
            ),
            trustLevel = trust,
        )

    // ── trustLabel ─────────────────────────────────────────────────────────────

    @Test
    fun trustLabel_returnsTrusted_forTrustedContact() {
        val contacts = listOf(fakeEntry(NODE_A, TrustLevel.TRUSTED))
        assertEquals("Trusted", trustLabel(contacts, NODE_A))
    }

    @Test
    fun trustLabel_returnsKnown_forKnownContact() {
        val contacts = listOf(fakeEntry(NODE_B, TrustLevel.KNOWN))
        assertEquals("Known", trustLabel(contacts, NODE_B))
    }

    @Test
    fun trustLabel_returnsBlocked_forBlockedContact() {
        val contacts = listOf(fakeEntry(NODE_C, TrustLevel.BLOCKED))
        assertEquals("Blocked", trustLabel(contacts, NODE_C))
    }

    @Test
    fun trustLabel_returnsStranger_forUnknownAuthor() {
        assertEquals("Stranger", trustLabel(emptyList(), NODE_X))
    }

    // ── ContentBody.previewText() ──────────────────────────────────────────────

    @Test
    fun previewText_returnsTitle_forArticle() {
        assertEquals("My Title", ContentBody.Article("My Title", "long body...").previewText())
    }

    @Test
    fun previewText_returnsTypeLabel_forImage() {
        assertEquals("Image", ContentBody.Image("data", ImageFormat.JPEG, 100, 100, "A photo").previewText())
    }

    @Test
    fun previewText_returnsTypeLabel_forImageWithNoCaption() {
        assertEquals("Image", ContentBody.Image("data", ImageFormat.JPEG, 100, 100, null).previewText())
    }

    @Test
    fun previewText_returnsContact_forContact() {
        assertEquals("Contact", ContentBody.Contact(bio = "Hello").previewText())
    }

    // ── ContentBody.fullText() ─────────────────────────────────────────────────

    @Test
    fun fullText_returnsTitleAndBody_forArticle() {
        assertEquals("Title\n\nBody text", ContentBody.Article("Title", "Body text").fullText())
    }

    @Test
    fun fullText_returnsCaption_forImageWithCaption() {
        assertEquals("Caption", ContentBody.Image("data", ImageFormat.JPEG, 100, 100, "Caption").fullText())
    }

    @Test
    fun fullText_returnsPlaceholder_forImageWithNoCaption() {
        assertEquals("(Image)", ContentBody.Image("data", ImageFormat.JPEG, 100, 100, null).fullText())
    }

    @Test
    fun fullText_returnsBio_forContactWithBio() {
        assertEquals("My bio", ContentBody.Contact(bio = "My bio").fullText())
    }

    @Test
    fun fullText_returnsEmpty_forContactWithNoBio() {
        assertEquals("", ContentBody.Contact().fullText())
    }
}
