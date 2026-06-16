package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.generated.ContactEncounterEntryRepositoryImpl
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

class ContactEncounterEntryRepositoryTest {

    private lateinit var db: ISQLite
    private val repo = ContactEncounterEntryRepositoryImpl

    @BeforeTest
    fun setUp() = runTest {
        db = sqlite(
            url = "freepath-test.db",
            options = ConnectionPool.Options(minConnections = 1, maxConnections = 1),
        ).apply {
            migrate(files = migrations).getOrThrow()
        }
        with(db) { repo.deleteAll().getOrThrow() }
    }

    @AfterTest
    fun tearDown() = runTest {
        db.close().getOrThrow()
    }

    private fun entry(peerId: String, count: Int = 1) = ContactEncounterEntry(
        peerId = peerId,
        lastSeenAt = Clock.System.now(),
        count = count,
    )

    @Test
    fun `insert assigns auto-increment id`() = runTest {
        with(db) {
            val saved = repo.insert(entry("peer-a")).getOrThrow()
            assertTrue(saved.id > 0)
        }
    }

    @Test
    fun `findOneByPeerId returns the inserted row`() = runTest {
        with(db) {
            repo.insert(entry("peer-a", count = 3)).getOrThrow()
            val found = assertNotNull(repo.findOneByPeerId("peer-a").getOrThrow())
            assertEquals("peer-a", found.peerId)
            assertEquals(3, found.count)
        }
    }

    @Test
    fun `findOneByPeerId returns null for an unknown peer`() = runTest {
        with(db) {
            assertNull(repo.findOneByPeerId("nobody").getOrThrow())
        }
    }

    @Test
    fun `peer_id unique index rejects a duplicate`() = runTest {
        with(db) {
            repo.insert(entry("dup")).getOrThrow()
            assertFails { repo.insert(entry("dup")).getOrThrow() }
        }
    }

    @Test
    fun `update bumps the encounter count`() = runTest {
        with(db) {
            val inserted = repo.insert(entry("peer-a", count = 1)).getOrThrow()
            val updated = repo.update(
                inserted.copy(count = 2, lastSeenAt = Clock.System.now()),
            ).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals(2, updated.count)

            val refetched = assertNotNull(repo.findOneByPeerId("peer-a").getOrThrow())
            assertEquals(2, refetched.count)
        }
    }

    @Test
    fun `save with existing id updates in place`() = runTest {
        with(db) {
            val inserted = repo.insert(entry("peer-a", count = 1)).getOrThrow()
            repo.save(inserted.copy(count = 5)).getOrThrow()

            val refetched = assertNotNull(repo.findOneByPeerId("peer-a").getOrThrow())
            assertEquals(5, refetched.count)
        }
    }

    @Test
    fun `deleteAll clears the table`() = runTest {
        with(db) {
            repo.insert(entry("peer-a")).getOrThrow()
            repo.insert(entry("peer-b")).getOrThrow()
            repo.deleteAll().getOrThrow()

            assertNull(repo.findOneByPeerId("peer-a").getOrThrow())
            assertNull(repo.findOneByPeerId("peer-b").getOrThrow())
        }
    }

    @Test
    fun `inserted row round-trips lastSeenAt and count`() = runTest {
        with(db) {
            val seenAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
            repo.insert(ContactEncounterEntry(peerId = "peer-x", lastSeenAt = seenAt, count = 7)).getOrThrow()

            val found = assertNotNull(repo.findOneByPeerId("peer-x").getOrThrow())
            assertEquals(seenAt, found.lastSeenAt)
            assertEquals(7, found.count)
        }
    }

    @Test
    fun `count defaults to one`() = runTest {
        with(db) {
            repo.insert(ContactEncounterEntry(peerId = "peer-default")).getOrThrow()
            assertEquals(1, assertNotNull(repo.findOneByPeerId("peer-default").getOrThrow()).count)
        }
    }

    @Test
    fun `delete removes only the targeted entry`() = runTest {
        with(db) {
            val a = repo.insert(entry("peer-a")).getOrThrow()
            repo.insert(entry("peer-b")).getOrThrow()

            repo.delete(a).getOrThrow()

            assertNull(repo.findOneByPeerId("peer-a").getOrThrow())
            assertNotNull(repo.findOneByPeerId("peer-b").getOrThrow())
        }
    }
}
