package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.generated.ContactRoutingEntryRepositoryImpl
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

class ContactRoutingEntryRepositoryTest {

    private lateinit var db: ISQLite
    private val repo = ContactRoutingEntryRepositoryImpl

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

    private fun entry(
        peerId: String,
        bleUpdatedAt: Instant? = null,
        bleIdentitySecret: String? = null,
    ) = ContactRoutingEntry(
        peerId = peerId,
        bleUpdatedAt = bleUpdatedAt,
        bleIdentitySecret = bleIdentitySecret,
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
            repo.insert(entry("peer-a")).getOrThrow()
            val found = assertNotNull(repo.findOneByPeerId("peer-a").getOrThrow())
            assertEquals("peer-a", found.peerId)
        }
    }

    @Test
    fun `findOneByPeerId returns null for an unknown peer`() = runTest {
        with(db) {
            assertNull(repo.findOneByPeerId("nobody").getOrThrow())
        }
    }

    @Test
    fun `inserted row round-trips the ble fields`() = runTest {
        with(db) {
            val updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
            repo.insert(
                entry("peer-x", bleUpdatedAt = updatedAt, bleIdentitySecret = "secret-x"),
            ).getOrThrow()

            val found = assertNotNull(repo.findOneByPeerId("peer-x").getOrThrow())
            assertEquals(updatedAt, found.bleUpdatedAt)
            assertEquals("secret-x", found.bleIdentitySecret)
        }
    }

    @Test
    fun `findAllByIdentitySecretNotNull returns only rows with a secret`() = runTest {
        with(db) {
            repo.insert(entry("peer-a", bleIdentitySecret = "secret-a")).getOrThrow()
            repo.insert(entry("peer-b", bleIdentitySecret = "secret-b")).getOrThrow()
            repo.insert(entry("peer-c")).getOrThrow()

            val withSecret = repo.findAllByIdentitySecretNotNull().getOrThrow()
            assertEquals(2, withSecret.size)
            assertTrue(withSecret.all { it.bleIdentitySecret != null })
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
    fun `update changes the ble identity secret`() = runTest {
        with(db) {
            val inserted = repo.insert(entry("peer-a")).getOrThrow()
            val updated = repo.update(inserted.copy(bleIdentitySecret = "rotated")).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals("rotated", updated.bleIdentitySecret)

            val refetched = assertNotNull(repo.findOneByPeerId("peer-a").getOrThrow())
            assertEquals("rotated", refetched.bleIdentitySecret)
        }
    }

    @Test
    fun `save with existing id updates in place`() = runTest {
        with(db) {
            val inserted = repo.insert(entry("peer-a")).getOrThrow()
            repo.save(inserted.copy(bleIdentitySecret = "saved")).getOrThrow()

            val refetched = assertNotNull(repo.findOneByPeerId("peer-a").getOrThrow())
            assertEquals("saved", refetched.bleIdentitySecret)
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
}
