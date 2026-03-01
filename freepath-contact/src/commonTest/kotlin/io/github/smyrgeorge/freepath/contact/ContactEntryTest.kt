package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class ContactEntryTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeEntry(
        nodeId: String = "testNodeId",
        card: ContactCard? = null,
        trustLevel: TrustLevel = TrustLevel.TRUSTED,
        addedAt: Long = 1_000L,
        name: String? = null,
        lastSeenAt: Long? = null,
        notes: String? = null,
        pinned: Boolean = false,
        muted: Boolean = false,
        tags: List<String> = emptyList(),
    ): ContactEntry {
        val actualCard = card ?: run {
            val kp = CryptoProvider.generateEd25519KeyPair()
            val encKp = CryptoProvider.generateX25519KeyPair()
            ContactCard(
                schema = ContactCard.SCHEMA,
                nodeId = nodeId,
                sigKey = Base64.encode(kp.publicKey),
                encKey = Base64.encode(encKp.publicKey),
                updatedAt = 1_000L,
            )
        }
        return ContactEntry(
            nodeId = nodeId,
            card = actualCard,
            trustLevel = trustLevel,
            addedAt = addedAt,
            name = name,
            lastSeenAt = lastSeenAt,
            notes = notes,
            pinned = pinned,
            muted = muted,
            tags = tags,
        )
    }

    // ── notes validation ──────────────────────────────────────────────────────

    @Test
    fun contactEntry_validation_acceptsValidNotes() {
        val entry = makeEntry(notes = "My notes")
        assertEquals("My notes", entry.notes)
    }

    @Test
    fun contactEntry_validation_acceptsMaxNotesLength() {
        val maxNotes = "a".repeat(ContactEntry.MAX_NOTES_LENGTH)
        val entry = makeEntry(notes = maxNotes)
        assertEquals(ContactEntry.MAX_NOTES_LENGTH, entry.notes?.length)
    }

    @Test
    fun contactEntry_validation_rejectsNotesTooLong() {
        val longNotes = "a".repeat(ContactEntry.MAX_NOTES_LENGTH + 1)
        assertFails {
            makeEntry(notes = longNotes)
        }
    }

    // ── tags validation ───────────────────────────────────────────────────────

    @Test
    fun contactEntry_validation_acceptsValidTags() {
        val entry = makeEntry(tags = listOf("family", "work"))
        assertEquals(listOf("family", "work"), entry.tags)
    }

    @Test
    fun contactEntry_validation_acceptsMaxTagsCount() {
        val maxTags = List(ContactEntry.MAX_TAGS_COUNT) { "tag$it" }
        val entry = makeEntry(tags = maxTags)
        assertEquals(ContactEntry.MAX_TAGS_COUNT, entry.tags.size)
    }

    @Test
    fun contactEntry_validation_rejectsTooManyTags() {
        val tooManyTags = List(ContactEntry.MAX_TAGS_COUNT + 1) { "tag$it" }
        assertFails {
            makeEntry(tags = tooManyTags)
        }
    }

    @Test
    fun contactEntry_validation_acceptsMaxTagLength() {
        val maxTag = "a".repeat(ContactEntry.MAX_TAG_LENGTH)
        val entry = makeEntry(tags = listOf(maxTag))
        assertEquals(ContactEntry.MAX_TAG_LENGTH, entry.tags.first().length)
    }

    @Test
    fun contactEntry_validation_rejectsTagTooLong() {
        val longTag = "a".repeat(ContactEntry.MAX_TAG_LENGTH + 1)
        assertFails {
            makeEntry(tags = listOf(longTag))
        }
    }

    // ── merge ─────────────────────────────────────────────────────────────────

    @Test
    fun merge_returnsNewEntryWithIncomingCard() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val storedCard = ContactCard(
            schema = ContactCard.SCHEMA,
            nodeId = "testId",
            sigKey = Base64.encode(kp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = 1_000L,
            name = "Old Name",
            bio = "Old bio",
        )
        val stored = makeEntry(nodeId = "testId", card = storedCard, name = "Local Name", notes = "My notes")

        val incomingCard = storedCard.copy(
            updatedAt = 2_000L,
            name = "New Name",
            bio = "New bio",
        )
        val incoming = makeEntry(nodeId = "testId", card = incomingCard, name = "Other Local Name")

        val merged = stored.merge(incoming)

        assertEquals(incomingCard, merged.card)
        assertEquals("New Name", merged.card.name)
        assertEquals("New bio", merged.card.bio)
    }

    @Test
    fun merge_preservesLocalOnlyFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val storedCard = ContactCard(
            schema = ContactCard.SCHEMA,
            nodeId = "testId",
            sigKey = Base64.encode(kp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = 1_000L,
        )
        val stored = makeEntry(
            nodeId = "testId",
            card = storedCard,
            trustLevel = TrustLevel.KNOWN,
            addedAt = 100L,
            name = "Local Name",
            lastSeenAt = 500L,
            notes = "My notes",
            pinned = true,
            muted = true,
            tags = listOf("family"),
        )

        val incomingCard = storedCard.copy(updatedAt = 2_000L, name = "New Name")
        val incoming = makeEntry(nodeId = "testId", card = incomingCard)

        val merged = stored.merge(incoming)

        // Card fields updated
        assertEquals("New Name", merged.card.name)
        // Local-only fields preserved
        assertEquals(TrustLevel.KNOWN, merged.trustLevel)
        assertEquals(100L, merged.addedAt)
        assertEquals("Local Name", merged.name)
        assertEquals(500L, merged.lastSeenAt)
        assertEquals("My notes", merged.notes)
        assertEquals(true, merged.pinned)
        assertEquals(true, merged.muted)
        assertEquals(listOf("family"), merged.tags)
    }

    @Test
    fun merge_throwsForDifferentNodeId() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val storedCard = ContactCard(
            schema = ContactCard.SCHEMA,
            nodeId = "testId1",
            sigKey = Base64.encode(kp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = 1_000L,
        )
        val stored = makeEntry(nodeId = "testId1", card = storedCard)

        val incomingCard = storedCard.copy(nodeId = "testId2", updatedAt = 2_000L)
        val incoming = makeEntry(nodeId = "testId2", card = incomingCard)

        assertFailsWith<IllegalArgumentException> {
            stored.merge(incoming)
        }
    }

    @Test
    fun merge_throwsForSameUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val storedCard = ContactCard(
            schema = ContactCard.SCHEMA,
            nodeId = "testId",
            sigKey = Base64.encode(kp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = 1_000L,
        )
        val stored = makeEntry(nodeId = "testId", card = storedCard)

        val incomingCard = storedCard.copy(updatedAt = 1_000L)
        val incoming = makeEntry(nodeId = "testId", card = incomingCard)

        assertFailsWith<IllegalArgumentException> {
            stored.merge(incoming)
        }
    }

    @Test
    fun merge_throwsForOlderUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val storedCard = ContactCard(
            schema = ContactCard.SCHEMA,
            nodeId = "testId",
            sigKey = Base64.encode(kp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = 2_000L,
        )
        val stored = makeEntry(nodeId = "testId", card = storedCard)

        val incomingCard = storedCard.copy(updatedAt = 1_000L)
        val incoming = makeEntry(nodeId = "testId", card = incomingCard)

        assertFailsWith<IllegalArgumentException> {
            stored.merge(incoming)
        }
    }
}
