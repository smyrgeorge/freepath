package io.github.smyrgeorge.freepath.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Clock

class RelayOfferedEntryTest {

    @Test
    fun `a fresh entry has no id`() {
        val entry = RelayOfferedEntry(relayEntryId = 1, peerId = "peer-a")
        assertEquals(0, entry.id)
    }

    @Test
    fun `entries with the same values are equal`() {
        val now = Clock.System.now()
        val a = RelayOfferedEntry(id = 1, createdAt = now, updatedAt = now, relayEntryId = 7, peerId = "p")
        val b = RelayOfferedEntry(id = 1, createdAt = now, updatedAt = now, relayEntryId = 7, peerId = "p")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `entries for different replicas or peers are not equal`() {
        val now = Clock.System.now()
        val a = RelayOfferedEntry(id = 1, createdAt = now, updatedAt = now, relayEntryId = 7, peerId = "p")
        assertNotEquals(a, a.copy(relayEntryId = 8))
        assertNotEquals(a, a.copy(peerId = "q"))
    }
}
