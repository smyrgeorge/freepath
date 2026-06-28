package io.github.smyrgeorge.freepath.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class ContactRoutingEntryTest {

    @Test
    fun `a fresh entry has no id and no ble fields`() {
        val entry = ContactRoutingEntry(peerId = "peer-a")
        assertEquals(0, entry.id)
        assertNull(entry.bleUpdatedAt)
        assertNull(entry.bleIdentitySecret)
    }

    @Test
    fun `entries with the same values are equal`() {
        val now = Clock.System.now()
        val a = ContactRoutingEntry(
            id = 1,
            createdAt = now,
            updatedAt = now,
            peerId = "p",
            bleUpdatedAt = now,
            bleIdentitySecret = "secret",
        )
        val b = ContactRoutingEntry(
            id = 1,
            createdAt = now,
            updatedAt = now,
            peerId = "p",
            bleUpdatedAt = now,
            bleIdentitySecret = "secret",
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `entries for different peers are not equal`() {
        val now = Clock.System.now()
        val a = ContactRoutingEntry(id = 1, createdAt = now, updatedAt = now, peerId = "p1")
        val b = a.copy(peerId = "p2")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy alters the ble identity secret while preserving the peer`() {
        val entry = ContactRoutingEntry(peerId = "peer-a")
        val updated = entry.copy(bleIdentitySecret = "rotated")
        assertEquals("rotated", updated.bleIdentitySecret)
        assertEquals("peer-a", updated.peerId)
        assertNull(entry.bleIdentitySecret)
    }
}
