package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ContactEntryTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeContact(updatedAt: Instant = Clock.System.now()): Contact {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        return Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(kp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = updatedAt,
        )
    }

    private fun makeEntry(
        contact: Contact? = null,
        trustLevel: TrustLevel = TrustLevel.TRUSTED,
        name: String? = null,
        lastSeenAt: Instant? = null,
        notes: String? = null,
        pinned: Boolean = false,
        muted: Boolean = false,
        tags: List<String> = emptyList(),
    ): ContactEntry {
        val actualContact = contact ?: makeContact()
        return ContactEntry(
            peerId = actualContact.peerId,
            contact = actualContact,
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
    fun merge_returnsNewEntryWithIncomingContact() {
        val now = Clock.System.now()
        val storedContact = makeContact(updatedAt = now).copy(name = "Old Name")
        val stored = makeEntry(contact = storedContact, name = "Local Name", notes = "My notes")

        val incomingContact = storedContact.copy(updatedAt = now + 1000.milliseconds, name = "New Name")
        val incoming = makeEntry(contact = incomingContact, name = "Other Local Name")

        val merged = stored.merge(incoming)

        assertEquals(incomingContact, merged.contact)
        assertEquals("New Name", merged.contact.name)
    }

    @Test
    fun merge_preservesLocalOnlyFields() {
        val now = Clock.System.now()
        val storedContact = makeContact(updatedAt = now)
        val stored = makeEntry(
            contact = storedContact,
            trustLevel = TrustLevel.KNOWN,
            name = "Local Name",
            lastSeenAt = Clock.System.now(),
            notes = "My notes",
            pinned = true,
            muted = true,
            tags = listOf("family"),
        )

        val incomingContact = storedContact.copy(updatedAt = now + 1000.milliseconds, name = "New Name")
        val incoming = makeEntry(contact = incomingContact)

        val merged = stored.merge(incoming)

        assertEquals("New Name", merged.contact.name)
        assertEquals(TrustLevel.KNOWN, merged.trustLevel)
        assertEquals("Local Name", merged.name)
        assertEquals(stored.lastSeenAt, merged.lastSeenAt)
        assertEquals("My notes", merged.notes)
        assertEquals(true, merged.pinned)
        assertEquals(true, merged.muted)
        assertEquals(listOf("family"), merged.tags)
    }

    @Test
    fun merge_throwsForDifferentPeerId() {
        val now = Clock.System.now()
        val contact1 = makeContact(updatedAt = now)
        val contact2 = makeContact(updatedAt = now + 1000.milliseconds)
        val stored = makeEntry(contact = contact1)
        val incoming = makeEntry(contact = contact2)

        assertFailsWith<IllegalArgumentException> {
            stored.merge(incoming)
        }
    }

    @Test
    fun merge_throwsForSameUpdatedAt() {
        val now = Clock.System.now()
        val storedContact = makeContact(updatedAt = now)
        val stored = makeEntry(contact = storedContact)
        val incoming = makeEntry(contact = storedContact.copy(updatedAt = now))

        assertFailsWith<IllegalArgumentException> {
            stored.merge(incoming)
        }
    }

    @Test
    fun merge_throwsForOlderUpdatedAt() {
        val now = Clock.System.now()
        val storedContact = makeContact(updatedAt = now)
        val stored = makeEntry(contact = storedContact)
        val incoming = makeEntry(contact = storedContact.copy(updatedAt = now - 1000.milliseconds))

        assertFailsWith<IllegalArgumentException> {
            stored.merge(incoming)
        }
    }
}
