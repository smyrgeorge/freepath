package io.github.smyrgeorge.freepath.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Clock

class ContactEncounterEntryTest {

    @Test
    fun `a fresh entry has no id and a count of one`() {
        val entry = ContactEncounterEntry(peerId = "peer-a")
        assertEquals(0, entry.id)
        assertEquals(1, entry.count)
    }

    @Test
    fun `entries with the same values are equal`() {
        val now = Clock.System.now()
        val a = ContactEncounterEntry(id = 1, createdAt = now, updatedAt = now, peerId = "p", lastSeenAt = now, count = 2)
        val b = ContactEncounterEntry(id = 1, createdAt = now, updatedAt = now, peerId = "p", lastSeenAt = now, count = 2)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `entries for different peers are not equal`() {
        val now = Clock.System.now()
        val a = ContactEncounterEntry(id = 1, createdAt = now, updatedAt = now, peerId = "p1", lastSeenAt = now)
        val b = a.copy(peerId = "p2")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy bumps the count while preserving the peer`() {
        val entry = ContactEncounterEntry(peerId = "peer-a", count = 1)
        val bumped = entry.copy(count = entry.count + 1)
        assertEquals(2, bumped.count)
        assertEquals("peer-a", bumped.peerId)
    }
}
