package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.database.RelayEntry.Companion.toRelayEntry
import io.github.smyrgeorge.freepath.database.generated.RelayEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.libnet.client.model.RelayMetadata
import io.github.smyrgeorge.freepath.libnet.client.model.StatelessEnvelope
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class RelayEntryRepositoryTest {

    private lateinit var db: ISQLite
    private val repo = RelayEntryRepositoryImpl

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun envelope(
        receiverIdHash: ByteArray = CryptoProvider.randomBytes(32),
        ttl: Int = 5,
    ) = StatelessEnvelope(
        schema = 2,
        receiverIdHash = receiverIdHash,
        timestamp = Clock.System.now(),
        nonce = CryptoProvider.randomBytes(12),
        ephemeralKey = CryptoProvider.randomBytes(32),
        payload = byteArrayOf(1, 2, 3, 4),
        relay = RelayMetadata(ttl = ttl, messageId = CryptoProvider.randomBytes(32), priority = 1),
    )

    private fun entry(
        receiverIdHash: ByteArray = CryptoProvider.randomBytes(32),
        ttl: Int = 5,
    ) = envelope(receiverIdHash, ttl).toRelayEntry()

    // ── insert ────────────────────────────────────────────────────────────────

    @Test
    fun `insert assigns auto-increment id`() = runTest {
        with(db) {
            val saved = repo.insert(entry()).getOrThrow()
            assertTrue(saved.id > 0, "Expected auto-assigned id > 0, got ${saved.id}")
        }
    }

    @Test
    fun `insert persists envelope fields`() = runTest {
        with(db) {
            val hash = CryptoProvider.randomBytes(32)
            repo.insert(entry(receiverIdHash = hash, ttl = 5)).getOrThrow()

            val fetched = repo.findAllByLimit(100).getOrThrow().first()
            assertTrue(fetched.envelope.receiverIdHash.contentEquals(hash))
            assertEquals(2, fetched.envelope.schema)
            assertEquals(5, fetched.ttl)
            assertEquals(1, fetched.envelope.relay?.priority)
        }
    }

    @Test
    fun `insert persists large payload`() = runTest {
        with(db) {
            val payload = ByteArray(1024) { it.toByte() }
            val e = envelope().copy(payload = payload)
            repo.insert(RelayEntry(envelope = e)).getOrThrow()

            val fetched = repo.findAllByLimit(100).getOrThrow().first()
            assertTrue(fetched.envelope.payload.contentEquals(payload))
            assertEquals(1024, fetched.envelope.payload.size)
        }
    }

    @Test
    fun `insert ids are unique and increasing`() = runTest {
        with(db) {
            val a = repo.insert(entry()).getOrThrow()
            val b = repo.insert(entry()).getOrThrow()
            assertTrue(b.id > a.id)
        }
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update persists changed ttl`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(ttl = 5)).getOrThrow()
            val newEnvelope = inserted.envelope.copy(relay = inserted.envelope.relay!!.copy(ttl = 3))
            val updated = repo.update(inserted.copy(envelope = newEnvelope)).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals(3, updated.ttl)
            assertEquals(3, updated.envelope.relay?.ttl)
        }
    }

    @Test
    fun `update sets updatedAt to a more recent timestamp`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(ttl = 5)).getOrThrow()
            val newEnvelope = inserted.envelope.copy(relay = inserted.envelope.relay!!.copy(ttl = 4))
            val updated = repo.update(inserted.copy(envelope = newEnvelope)).getOrThrow()
            assertTrue(updated.updatedAt >= inserted.updatedAt)
        }
    }

    // ── save ─────────────────────────────────────────────────────────────────

    @Test
    fun `save with id=0 inserts new entry`() = runTest {
        with(db) {
            val saved = repo.save(entry()).getOrThrow()
            assertTrue(saved.id > 0)
        }
    }

    @Test
    fun `save with existing id updates entry`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(ttl = 5)).getOrThrow()
            val newEnvelope = inserted.envelope.copy(relay = inserted.envelope.relay!!.copy(ttl = 2))
            val updated = repo.save(inserted.copy(envelope = newEnvelope)).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals(2, updated.ttl)
        }
    }

    // ── TTL update via save ───────────────────────────────────────────────────

    @Test
    fun `save with decremented ttl persists new ttl and generated column`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(ttl = 5)).getOrThrow()
            val newEnvelope = inserted.envelope.copy(relay = inserted.envelope.relay!!.copy(ttl = 4))
            val updated = repo.save(inserted.copy(envelope = newEnvelope)).getOrThrow()

            assertEquals(4, updated.ttl)
            assertEquals(4, updated.envelope.relay?.ttl)
            assertTrue(updated.updatedAt >= inserted.updatedAt)
        }
    }

    @Test
    fun `save with ttl=0 does not delete the entry`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(ttl = 1)).getOrThrow()
            val newEnvelope = inserted.envelope.copy(relay = inserted.envelope.relay!!.copy(ttl = 0))
            repo.save(inserted.copy(envelope = newEnvelope)).getOrThrow()

            val remaining = repo.findAllByLimit(100).getOrThrow()
            assertEquals(1, remaining.size)
            assertEquals(0, remaining.first().ttl)
        }
    }

    @Test
    fun `save with updated ttl does not affect other envelope fields`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(ttl = 5)).getOrThrow()
            val newEnvelope = inserted.envelope.copy(relay = inserted.envelope.relay!!.copy(ttl = 3))
            val updated = repo.save(inserted.copy(envelope = newEnvelope)).getOrThrow()

            assertEquals(3, updated.ttl)
            assertTrue(updated.envelope.relay!!.messageId.contentEquals(inserted.envelope.relay!!.messageId))
            assertEquals(inserted.envelope.relay!!.priority, updated.envelope.relay!!.priority)
            assertTrue(updated.envelope.receiverIdHash.contentEquals(inserted.envelope.receiverIdHash))
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes the entry from the table`() = runTest {
        with(db) {
            val inserted = repo.insert(entry()).getOrThrow()
            repo.delete(inserted).getOrThrow()
            val remaining = repo.findAllByLimit(100).getOrThrow()
            assertTrue(remaining.isEmpty())
        }
    }

    // ── batchInsert ───────────────────────────────────────────────────────────

    @Test
    fun `batchInsert assigns ids to all entries`() = runTest {
        with(db) {
            val saved = repo.batchInsert(listOf(entry(), entry(), entry())).getOrThrow()
            assertEquals(3, saved.size)
            assertTrue(saved.all { it.id > 0 })
        }
    }

    @Test
    fun `batchInsert with empty list returns empty result`() = runTest {
        with(db) {
            val saved = repo.batchInsert(emptyList()).getOrThrow()
            assertTrue(saved.isEmpty())
        }
    }

    // ── batchUpdate ───────────────────────────────────────────────────────────

    @Test
    fun `batchUpdate persists changed fields for all entries`() = runTest {
        with(db) {
            val inserted = repo.batchInsert(listOf(entry(ttl = 5), entry(ttl = 5))).getOrThrow()
            val modified = inserted.map { e ->
                e.copy(envelope = e.envelope.copy(relay = e.envelope.relay!!.copy(ttl = 2)))
            }
            val updated = repo.batchUpdate(modified).getOrThrow()
            assertEquals(2, updated.size)
            assertTrue(updated.all { it.ttl == 2 })
        }
    }

    @Test
    fun `batchUpdate with empty list returns empty result`() = runTest {
        with(db) {
            val updated = repo.batchUpdate(emptyList()).getOrThrow()
            assertTrue(updated.isEmpty())
        }
    }

    // ── findAllByLimit ────────────────────────────────────────────────────────

    @Test
    fun `findAllByLimit returns all entries`() = runTest {
        with(db) {
            repeat(3) { repo.insert(entry()).getOrThrow() }
            val result = repo.findAllByLimit(100).getOrThrow()
            assertEquals(3, result.size)
        }
    }

    @Test
    fun `findAllByLimit respects limit`() = runTest {
        with(db) {
            repeat(10) { repo.insert(entry()).getOrThrow() }
            val result = repo.findAllByLimit(3).getOrThrow()
            assertEquals(3, result.size)
        }
    }

    @Test
    fun `findAllByLimit returns entries ordered by id ascending`() = runTest {
        with(db) {
            repeat(3) { repo.insert(entry()).getOrThrow() }
            val result = repo.findAllByLimit(100).getOrThrow()
            assertEquals(result.sortedBy { it.id }, result)
        }
    }

    @Test
    fun `findAllByLimit returns empty list when table is empty`() = runTest {
        with(db) {
            val result = repo.findAllByLimit(100).getOrThrow()
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `findAllByLimit includes entries with ttl = 0`() = runTest {
        with(db) {
            repo.insert(entry(ttl = 0)).getOrThrow()
            val result = repo.findAllByLimit(100).getOrThrow()
            assertEquals(1, result.size)
            assertEquals(0, result.first().ttl)
        }
    }

    // ── deleteById ────────────────────────────────────────────────────────────

    @Test
    fun `deleteById removes only the entry with that id`() = runTest {
        with(db) {
            val a = repo.insert(entry()).getOrThrow()
            val b = repo.insert(entry()).getOrThrow()

            repo.deleteById(a.id).getOrThrow()

            val remaining = repo.findAllByLimit(100).getOrThrow()
            assertEquals(1, remaining.size)
            assertEquals(b.id, remaining.first().id)
        }
    }

    // ── deleteAll ─────────────────────────────────────────────────────────────

    @Test
    fun `deleteAll removes every entry in the table`() = runTest {
        with(db) {
            repeat(5) { repo.insert(entry()).getOrThrow() }
            repo.deleteAll().getOrThrow()
            val remaining = repo.findAllByLimit(100).getOrThrow()
            assertTrue(remaining.isEmpty())
        }
    }

    @Test
    fun `deleteAll on empty table succeeds and returns 0`() = runTest {
        with(db) {
            val deleted = repo.deleteAll().getOrThrow()
            assertEquals(0L, deleted)
        }
    }

    // ── executeDeleteExpiredTtl ───────────────────────────────────────────────

    @Test
    fun `executeDeleteExpiredTtl removes entries where ttl = 0`() = runTest {
        with(db) {
            repo.insert(entry(ttl = 0)).getOrThrow()
            repo.insert(entry(ttl = 0)).getOrThrow()
            repo.insert(entry(ttl = 3)).getOrThrow()

            val deleted = repo.executeDeleteExpiredTtl().getOrThrow()
            assertEquals(2L, deleted)

            val remaining = repo.findAllByLimit(100).getOrThrow()
            assertEquals(1, remaining.size)
            assertEquals(3, remaining.first().ttl)
        }
    }

    @Test
    fun `executeDeleteExpiredTtl does not remove entries with positive ttl`() = runTest {
        with(db) {
            repeat(3) { repo.insert(entry(ttl = 5)).getOrThrow() }

            val deleted = repo.executeDeleteExpiredTtl().getOrThrow()
            assertEquals(0L, deleted)

            val remaining = repo.findAllByLimit(100).getOrThrow()
            assertEquals(3, remaining.size)
        }
    }

    @Test
    fun `executeDeleteExpiredTtl on empty table succeeds and returns 0`() = runTest {
        with(db) {
            val deleted = repo.executeDeleteExpiredTtl().getOrThrow()
            assertEquals(0L, deleted)
        }
    }

    // ── envelope roundtrip ────────────────────────────────────────────────────

    @Test
    fun `stored envelope roundtrips all fields correctly`() = runTest {
        with(db) {
            val original = envelope()
            val inserted = repo.insert(RelayEntry(envelope = original)).getOrThrow()
            val fetched = repo.findAllByLimit(1).getOrThrow().first()

            assertEquals(inserted.id, fetched.id)
            assertTrue(fetched.envelope.receiverIdHash.contentEquals(original.receiverIdHash))
            assertTrue(fetched.envelope.nonce.contentEquals(original.nonce))
            assertTrue(fetched.envelope.ephemeralKey.contentEquals(original.ephemeralKey))
            assertTrue(fetched.envelope.payload.contentEquals(original.payload))
            assertNotNull(fetched.envelope.relay)
            assertTrue(fetched.envelope.relay!!.messageId.contentEquals(original.relay!!.messageId))
            assertEquals(original.relay!!.ttl, fetched.envelope.relay!!.ttl)
            assertEquals(original.relay!!.priority, fetched.envelope.relay!!.priority)
        }
    }
}
