package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ContactCardEntryTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeCard(updatedAt: Instant = Clock.System.now()): ContactCard {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        return ContactCard(
            schema = ContactCard.SCHEMA,
            sigKey = Base64.encode(kp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = updatedAt,
        )
    }

    private fun makeEntry(
        card: ContactCard? = null,
        trustLevel: TrustLevel = TrustLevel.TRUSTED,
        name: String? = null,
        lastSeenAt: Instant? = null,
        notes: String? = null,
        pinned: Boolean = false,
        muted: Boolean = false,
        tags: List<String> = emptyList(),
    ): ContactCardEntry {
        val actualCard = card ?: makeCard()
        return ContactCardEntry(
            nodeId = actualCard.nodeId,
            card = actualCard,
            trustLevel = trustLevel,
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
        val maxNotes = "a".repeat(ContactCardEntry.MAX_NOTES_LENGTH)
        val entry = makeEntry(notes = maxNotes)
        assertEquals(ContactCardEntry.MAX_NOTES_LENGTH, entry.notes?.length)
    }

    @Test
    fun contactEntry_validation_rejectsNotesTooLong() {
        val longNotes = "a".repeat(ContactCardEntry.MAX_NOTES_LENGTH + 1)
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
        val maxTags = List(ContactCardEntry.MAX_TAGS_COUNT) { "tag$it" }
        val entry = makeEntry(tags = maxTags)
        assertEquals(ContactCardEntry.MAX_TAGS_COUNT, entry.tags.size)
    }

    @Test
    fun contactEntry_validation_rejectsTooManyTags() {
        val tooManyTags = List(ContactCardEntry.MAX_TAGS_COUNT + 1) { "tag$it" }
        assertFails {
            makeEntry(tags = tooManyTags)
        }
    }

    @Test
    fun contactEntry_validation_acceptsMaxTagLength() {
        val maxTag = "a".repeat(ContactCardEntry.MAX_TAG_LENGTH)
        val entry = makeEntry(tags = listOf(maxTag))
        assertEquals(ContactCardEntry.MAX_TAG_LENGTH, entry.tags.first().length)
    }

    @Test
    fun contactEntry_validation_rejectsTagTooLong() {
        val longTag = "a".repeat(ContactCardEntry.MAX_TAG_LENGTH + 1)
        assertFails {
            makeEntry(tags = listOf(longTag))
        }
    }

    // ── merge ─────────────────────────────────────────────────────────────────

    @Test
    fun merge_returnsNewEntryWithIncomingCard() {
        val now = Clock.System.now()
        val storedCard = makeCard(updatedAt = now).copy(name = "Old Name")
        val stored = makeEntry(card = storedCard, name = "Local Name", notes = "My notes")

        val incomingCard = storedCard.copy(updatedAt = now + 1000.milliseconds, name = "New Name")
        val incoming = makeEntry(card = incomingCard, name = "Other Local Name")

        val merged = stored.merge(incoming)

        assertEquals(incomingCard, merged.card)
        assertEquals("New Name", merged.card.name)
    }

    @Test
    fun merge_preservesLocalOnlyFields() {
        val now = Clock.System.now()
        val storedCard = makeCard(updatedAt = now)
        val stored = makeEntry(
            card = storedCard,
            trustLevel = TrustLevel.KNOWN,
            name = "Local Name",
            lastSeenAt = Clock.System.now(),
            notes = "My notes",
            pinned = true,
            muted = true,
            tags = listOf("family"),
        )

        val incomingCard = storedCard.copy(updatedAt = now + 1000.milliseconds, name = "New Name")
        val incoming = makeEntry(card = incomingCard)

        val merged = stored.merge(incoming)

        assertEquals("New Name", merged.card.name)
        assertEquals(TrustLevel.KNOWN, merged.trustLevel)
        assertEquals("Local Name", merged.name)
        assertEquals(stored.lastSeenAt, merged.lastSeenAt)
        assertEquals("My notes", merged.notes)
        assertEquals(true, merged.pinned)
        assertEquals(true, merged.muted)
        assertEquals(listOf("family"), merged.tags)
    }

    @Test
    fun merge_throwsForDifferentNodeId() {
        val now = Clock.System.now()
        val card1 = makeCard(updatedAt = now)
        val card2 = makeCard(updatedAt = now + 1000.milliseconds)
        val stored = makeEntry(card = card1)
        val incoming = makeEntry(card = card2)

        assertFailsWith<IllegalArgumentException> {
            stored.merge(incoming)
        }
    }

    @Test
    fun merge_throwsForSameUpdatedAt() {
        val now = Clock.System.now()
        val storedCard = makeCard(updatedAt = now)
        val stored = makeEntry(card = storedCard)
        val incoming = makeEntry(card = storedCard.copy(updatedAt = now))

        assertFailsWith<IllegalArgumentException> {
            stored.merge(incoming)
        }
    }

    @Test
    fun merge_throwsForOlderUpdatedAt() {
        val now = Clock.System.now()
        val storedCard = makeCard(updatedAt = now)
        val stored = makeEntry(card = storedCard)
        val incoming = makeEntry(card = storedCard.copy(updatedAt = now - 1000.milliseconds))

        assertFailsWith<IllegalArgumentException> {
            stored.merge(incoming)
        }
    }
}
