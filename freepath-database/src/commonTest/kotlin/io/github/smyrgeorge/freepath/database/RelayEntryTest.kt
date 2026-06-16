package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.RelayEntry.Companion.toRelayEntry
import io.github.smyrgeorge.freepath.libnet.client.model.RelayMetadata
import io.github.smyrgeorge.freepath.libnet.client.model.StatelessEnvelope
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class RelayEntryTest {

    private fun envelope(
        withRelay: Boolean = true,
        copies: Int = 8,
        expiresAt: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
        messageId: ByteArray = CryptoProvider.randomBytes(32),
    ) = StatelessEnvelope(
        schema = 3,
        receiverIdHash = CryptoProvider.randomBytes(32),
        timestamp = Clock.System.now(),
        nonce = CryptoProvider.randomBytes(12),
        ephemeralKey = CryptoProvider.randomBytes(32),
        payload = byteArrayOf(1, 2, 3),
        relay = if (withRelay) RelayMetadata(
            messageId = messageId,
            priority = 1,
            copies = copies,
            expiresAt = expiresAt,
        ) else null,
    )

    // ── toRelayEntry / seeding ──────────────────────────────────────────────────

    @Test
    fun `toRelayEntry seeds copies and expiresAt from the envelope`() {
        val expiry = Instant.fromEpochMilliseconds(1_699_000_000_000)
        val entry = envelope(copies = 6, expiresAt = expiry).toRelayEntry()
        assertEquals(6, entry.copies)
        assertEquals(expiry, entry.expiresAt)
        assertEquals(0, entry.id, "a fresh entry has no assigned id")
    }

    @Test
    fun `toRelayEntry rejects an envelope without relay metadata`() {
        assertFails { envelope(withRelay = false).toRelayEntry() }
    }

    @Test
    fun `constructing a RelayEntry without relay metadata fails`() {
        assertFails { RelayEntry(envelope = envelope(withRelay = false)) }
    }

    // ── equals / hashCode ───────────────────────────────────────────────────────

    @Test
    fun `entries with the same id audit copies and envelope are equal`() {
        val env = envelope()
        val now = Clock.System.now()
        val a = RelayEntry(id = 1, createdAt = now, updatedAt = now, envelope = env)
        val b = RelayEntry(id = 1, createdAt = now, updatedAt = now, envelope = env)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `entries differing only in copies are not equal`() {
        val now = Clock.System.now()
        val a = RelayEntry(id = 1, createdAt = now, updatedAt = now, envelope = envelope(copies = 8))
        val b = a.copy(copies = 4)
        assertNotEquals(a, b)
    }

    @Test
    fun `entries with different envelopes are not equal`() {
        val now = Clock.System.now()
        val a = RelayEntry(id = 1, createdAt = now, updatedAt = now, envelope = envelope())
        val b = RelayEntry(id = 1, createdAt = now, updatedAt = now, envelope = envelope())
        assertNotEquals(a, b, "different random messageId/receiverIdHash ⇒ different envelope")
    }

    // ── toString ────────────────────────────────────────────────────────────────

    @Test
    fun `toString reports copies and expiresAt`() {
        val entry = envelope(copies = 3).toRelayEntry()
        val text = entry.toString()
        assertTrue(text.contains("copies=3"), text)
        assertTrue(text.contains("expiresAt="), text)
    }
}
