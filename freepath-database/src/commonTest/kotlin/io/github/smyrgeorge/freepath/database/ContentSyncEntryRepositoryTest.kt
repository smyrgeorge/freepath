package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.generated.ContentSyncEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ContentSyncEntryRepositoryTest {

    private lateinit var db: ISQLite
    private val repo = ContentSyncEntryRepositoryImpl

    @BeforeTest
    fun setUp() = runTest {
        db = sqlite(
            url = ":memory:",
            options = ConnectionPool.Options(minConnections = 1, maxConnections = 1),
        ).apply {
            migrate(files = migrations).getOrThrow()
        }
    }

    @AfterTest
    fun tearDown() = runTest {
        db.close().getOrThrow()
    }

    private fun entry(peerId: String, contentId: String, version: Int = 1) = ContentSyncEntry(
        peerId = peerId,
        contentId = contentId,
        version = version,
        syncedAt = Clock.System.now(),
    )

    @Test
    fun `insert assigns auto-increment id`() = runTest {
        with(db) {
            val saved = repo.insert(entry("peer-a", "content-1")).getOrThrow()
            assertTrue(saved.id > 0)
        }
    }

    @Test
    fun `findOneByPeerIdAndContentId returns the inserted row`() = runTest {
        with(db) {
            repo.insert(entry("peer-a", "content-1", version = 3)).getOrThrow()
            val found = assertNotNull(repo.findOneByPeerIdAndContentId("peer-a", "content-1").getOrThrow())
            assertEquals("peer-a", found.peerId)
            assertEquals("content-1", found.contentId)
            assertEquals(3, found.version)
        }
    }

    @Test
    fun `findOneByPeerIdAndContentId returns null for an unknown peer`() = runTest {
        with(db) {
            repo.insert(entry("peer-a", "content-1")).getOrThrow()
            assertNull(repo.findOneByPeerIdAndContentId("nobody", "content-1").getOrThrow())
        }
    }

    @Test
    fun `findOneByPeerIdAndContentId returns null for an unknown content`() = runTest {
        with(db) {
            repo.insert(entry("peer-a", "content-1")).getOrThrow()
            assertNull(repo.findOneByPeerIdAndContentId("peer-a", "content-2").getOrThrow())
        }
    }

    @Test
    fun `peer_id and content_id unique index rejects a duplicate pair`() = runTest {
        with(db) {
            repo.insert(entry("peer-a", "content-1")).getOrThrow()
            assertFails { repo.insert(entry("peer-a", "content-1")).getOrThrow() }
        }
    }

    @Test
    fun `same content under a different peer is allowed`() = runTest {
        with(db) {
            repo.insert(entry("peer-a", "content-1")).getOrThrow()
            val other = repo.insert(entry("peer-b", "content-1")).getOrThrow()
            assertTrue(other.id > 0)

            assertNotNull(repo.findOneByPeerIdAndContentId("peer-a", "content-1").getOrThrow())
            assertNotNull(repo.findOneByPeerIdAndContentId("peer-b", "content-1").getOrThrow())
        }
    }

    @Test
    fun `inserted row round-trips version and syncedAt`() = runTest {
        with(db) {
            val syncedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
            repo.insert(
                ContentSyncEntry(peerId = "peer-x", contentId = "content-x", version = 7, syncedAt = syncedAt),
            ).getOrThrow()

            val found = assertNotNull(repo.findOneByPeerIdAndContentId("peer-x", "content-x").getOrThrow())
            assertEquals(7, found.version)
            assertEquals(syncedAt, found.syncedAt)
        }
    }

    @Test
    fun `update bumps the version`() = runTest {
        with(db) {
            val inserted = repo.insert(entry("peer-a", "content-1", version = 1)).getOrThrow()
            val updated = repo.update(inserted.copy(version = 2)).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals(2, updated.version)

            val refetched = assertNotNull(repo.findOneByPeerIdAndContentId("peer-a", "content-1").getOrThrow())
            assertEquals(2, refetched.version)
        }
    }

    @Test
    fun `save with existing id updates in place`() = runTest {
        with(db) {
            val inserted = repo.insert(entry("peer-a", "content-1", version = 1)).getOrThrow()
            repo.save(inserted.copy(version = 5)).getOrThrow()

            val refetched = assertNotNull(repo.findOneByPeerIdAndContentId("peer-a", "content-1").getOrThrow())
            assertEquals(5, refetched.version)
        }
    }

    @Test
    fun `delete removes only the targeted entry`() = runTest {
        with(db) {
            val a = repo.insert(entry("peer-a", "content-1")).getOrThrow()
            repo.insert(entry("peer-b", "content-1")).getOrThrow()

            repo.delete(a).getOrThrow()

            assertNull(repo.findOneByPeerIdAndContentId("peer-a", "content-1").getOrThrow())
            assertNotNull(repo.findOneByPeerIdAndContentId("peer-b", "content-1").getOrThrow())
        }
    }

    @Test
    fun `deleteAll clears the table`() = runTest {
        with(db) {
            repo.insert(entry("peer-a", "content-1")).getOrThrow()
            repo.insert(entry("peer-b", "content-2")).getOrThrow()
            repo.deleteAll().getOrThrow()

            assertNull(repo.findOneByPeerIdAndContentId("peer-a", "content-1").getOrThrow())
            assertNull(repo.findOneByPeerIdAndContentId("peer-b", "content-2").getOrThrow())
        }
    }
}
