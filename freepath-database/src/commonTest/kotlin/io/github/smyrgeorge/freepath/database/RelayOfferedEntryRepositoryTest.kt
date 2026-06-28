package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.RelayEntry.Companion.toRelayEntry
import io.github.smyrgeorge.freepath.database.generated.RelayEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.RelayOfferedEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.libnet.client.model.RelayMetadata
import io.github.smyrgeorge.freepath.libnet.client.model.StatelessEnvelope
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class RelayOfferedEntryRepositoryTest {

    private lateinit var db: ISQLite
    private val repo = RelayOfferedEntryRepositoryImpl
    private val relayRepo = RelayEntryRepositoryImpl

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

    private fun offered(entryId: Int, peerId: String) = RelayOfferedEntry(relayEntryId = entryId, peerId = peerId)

    /** A live relay replica, so deleteOrphaned has something to preserve. */
    private fun relayEntry() = StatelessEnvelope(
        schema = 3,
        receiverIdHash = CryptoProvider.randomBytes(32),
        timestamp = Clock.System.now(),
        nonce = CryptoProvider.randomBytes(12),
        ephemeralKey = CryptoProvider.randomBytes(32),
        payload = byteArrayOf(1, 2, 3),
        relay = RelayMetadata(
            messageId = CryptoProvider.randomBytes(32),
            priority = 1,
            copies = 8,
            expiresAt = Clock.System.now() + 1.days,
        ),
    ).toRelayEntry()

    @Test
    fun `insert assigns auto-increment id`() = runTest {
        with(db) {
            val saved = repo.insert(offered(1, "peer-a")).getOrThrow()
            assertTrue(saved.id > 0)
        }
    }

    @Test
    fun `findAllByPeerId returns only that peer's rows`() = runTest {
        with(db) {
            repo.insert(offered(1, "peer-a")).getOrThrow()
            repo.insert(offered(2, "peer-a")).getOrThrow()
            repo.insert(offered(1, "peer-b")).getOrThrow()

            assertEquals(setOf(1, 2), repo.findAllByPeerId("peer-a").getOrThrow().map { it.relayEntryId }.toSet())
            assertEquals(setOf(1), repo.findAllByPeerId("peer-b").getOrThrow().map { it.relayEntryId }.toSet())
        }
    }

    @Test
    fun `findAllByPeerId is empty for an unknown peer`() = runTest {
        with(db) {
            assertTrue(repo.findAllByPeerId("nobody").getOrThrow().isEmpty())
        }
    }

    @Test
    fun `unique index rejects a duplicate replica-peer pair`() = runTest {
        with(db) {
            repo.insert(offered(1, "peer-a")).getOrThrow()
            assertFails { repo.insert(offered(1, "peer-a")).getOrThrow() }
        }
    }

    @Test
    fun `the same replica can be offered to different peers`() = runTest {
        with(db) {
            repo.insert(offered(1, "peer-a")).getOrThrow()
            repo.insert(offered(1, "peer-b")).getOrThrow()
            assertEquals(1, repo.findAllByPeerId("peer-a").getOrThrow().size)
            assertEquals(1, repo.findAllByPeerId("peer-b").getOrThrow().size)
        }
    }

    @Test
    fun `deleteOrphaned removes rows for gone replicas but keeps rows for live ones`() = runTest {
        with(db) {
            val live = relayRepo.insert(relayEntry()).getOrThrow()
            repo.insert(offered(live.id, "peer-a")).getOrThrow()   // live replica → must survive
            repo.insert(offered(999_999, "peer-a")).getOrThrow()   // no such relay row → orphan

            val removed = repo.executeDeleteOrphaned().getOrThrow()
            assertEquals(1L, removed)

            val remaining = repo.findAllByPeerId("peer-a").getOrThrow()
            assertEquals(setOf(live.id), remaining.map { it.relayEntryId }.toSet())
        }
    }

    @Test
    fun `deleteAll clears the table`() = runTest {
        with(db) {
            repo.insert(offered(1, "peer-a")).getOrThrow()
            repo.insert(offered(2, "peer-b")).getOrThrow()
            repo.deleteAll().getOrThrow()
            assertTrue(repo.findAllByPeerId("peer-a").getOrThrow().isEmpty())
            assertTrue(repo.findAllByPeerId("peer-b").getOrThrow().isEmpty())
        }
    }
}
