package io.github.smyrgeorge.freepath.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Clock

class ContentSyncEntryTest {

    @Test
    fun `a fresh entry has no id`() {
        val entry = ContentSyncEntry(peerId = "peer-a", contentId = "content-1", version = 1)
        assertEquals(0, entry.id)
    }

    @Test
    fun `entries with the same values are equal`() {
        val now = Clock.System.now()
        val a = ContentSyncEntry(
            id = 1,
            createdAt = now,
            updatedAt = now,
            peerId = "peer-a",
            contentId = "content-1",
            version = 2,
            syncedAt = now,
        )
        val b = ContentSyncEntry(
            id = 1,
            createdAt = now,
            updatedAt = now,
            peerId = "peer-a",
            contentId = "content-1",
            version = 2,
            syncedAt = now,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `entries for different peers are not equal`() {
        val now = Clock.System.now()
        val a = ContentSyncEntry(
            id = 1,
            createdAt = now,
            updatedAt = now,
            peerId = "peer-a",
            contentId = "content-1",
            version = 1,
            syncedAt = now,
        )
        val b = a.copy(peerId = "peer-b")
        assertNotEquals(a, b)
    }

    @Test
    fun `entries for different content are not equal`() {
        val now = Clock.System.now()
        val a = ContentSyncEntry(
            id = 1,
            createdAt = now,
            updatedAt = now,
            peerId = "peer-a",
            contentId = "content-1",
            version = 1,
            syncedAt = now,
        )
        val b = a.copy(contentId = "content-2")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy bumps the version while preserving peer and content`() {
        val entry = ContentSyncEntry(peerId = "peer-a", contentId = "content-1", version = 1)
        val bumped = entry.copy(version = entry.version + 1)
        assertEquals(2, bumped.version)
        assertEquals("peer-a", bumped.peerId)
        assertEquals("content-1", bumped.contentId)
    }
}
