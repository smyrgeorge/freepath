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
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

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
        copies: Int = 8,
        // Default far in the future so it never interferes with the sweep tests.
        expiresAt: Instant = Clock.System.now() + 1.days,
    ) = StatelessEnvelope(
        schema = 3,
        receiverIdHash = receiverIdHash,
        timestamp = Clock.System.now(),
        nonce = CryptoProvider.randomBytes(12),
        ephemeralKey = CryptoProvider.randomBytes(32),
        payload = byteArrayOf(1, 2, 3, 4),
        relay = RelayMetadata(
            messageId = CryptoProvider.randomBytes(32),
            priority = 1,
            copies = copies,
            expiresAt = expiresAt,
        ),
    )

    private fun entry(
        receiverIdHash: ByteArray = CryptoProvider.randomBytes(32),
        copies: Int = 8,
        expiresAt: Instant = Clock.System.now() + 1.days,
    ) = envelope(receiverIdHash, copies, expiresAt).toRelayEntry()

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

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
            repo.insert(entry(receiverIdHash = hash, copies = 8)).getOrThrow()

            val fetched = repo.findAllByLimit(100).getOrThrow().first()
            assertTrue(fetched.envelope.receiverIdHash.contentEquals(hash))
            assertEquals(3, fetched.envelope.schema)
            assertEquals(8, fetched.copies)
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

    // ── update / save (mutable copies budget) ───────────────────────────────────

    @Test
    fun `update persists changed copies`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(copies = 8)).getOrThrow()
            val updated = repo.update(inserted.copy(copies = 4)).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals(4, updated.copies)
        }
    }

    @Test
    fun `update sets updatedAt to a more recent timestamp`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(copies = 8)).getOrThrow()
            val updated = repo.update(inserted.copy(copies = 4)).getOrThrow()
            assertTrue(updated.updatedAt >= inserted.updatedAt)
        }
    }

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
            val inserted = repo.insert(entry(copies = 8)).getOrThrow()
            val updated = repo.save(inserted.copy(copies = 2)).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals(2, updated.copies)
        }
    }

    @Test
    fun `save with halved copies persists the new budget`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(copies = 8)).getOrThrow()
            val updated = repo.save(inserted.copy(copies = 4)).getOrThrow()
            assertEquals(4, updated.copies)
            assertTrue(updated.updatedAt >= inserted.updatedAt)
        }
    }

    @Test
    fun `save with copies=1 in the wait phase does not delete the entry`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(copies = 2)).getOrThrow()
            repo.save(inserted.copy(copies = 1)).getOrThrow()

            val remaining = repo.findAllByLimit(100).getOrThrow()
            assertEquals(1, remaining.size)
            assertEquals(1, remaining.first().copies)
        }
    }

    @Test
    fun `save with updated copies does not affect other envelope fields`() = runTest {
        with(db) {
            val inserted = repo.insert(entry(copies = 8)).getOrThrow()
            val updated = repo.save(inserted.copy(copies = 3)).getOrThrow()

            assertEquals(3, updated.copies)
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
            val inserted = repo.batchInsert(listOf(entry(copies = 8), entry(copies = 8))).getOrThrow()
            val modified = inserted.map { e -> e.copy(copies = 2) }
            val updated = repo.batchUpdate(modified).getOrThrow()
            assertEquals(2, updated.size)
            assertTrue(updated.all { it.copies == 2 })
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

    // ── findOneById ───────────────────────────────────────────────────────────

    @Test
    fun `findOneById returns the matching entry`() = runTest {
        with(db) {
            val inserted = repo.insert(entry()).getOrThrow()
            val found = assertNotNull(repo.findOneById(inserted.id).getOrThrow())
            assertEquals(inserted.id, found.id)
            assertTrue(found.envelope.relay!!.messageId.contentEquals(inserted.envelope.relay!!.messageId))
        }
    }

    @Test
    fun `findOneById returns null for a missing id`() = runTest {
        with(db) {
            repo.insert(entry()).getOrThrow()
            assertNull(repo.findOneById(999_999).getOrThrow())
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

    // ── executeDeleteExpired (lifecycle sweep) ────────────────────────────────

    @Test
    fun `executeDeleteExpired removes time-expired entries`() = runTest {
        with(db) {
            repo.insert(entry(expiresAt = Instant.fromEpochMilliseconds(1))).getOrThrow() // long past
            repo.insert(entry()).getOrThrow()                                             // future expiry

            val deleted = repo.executeDeleteExpired(now = nowMillis(), minCreatedAt = 0).getOrThrow()
            assertEquals(1L, deleted)
            assertEquals(1, repo.findAllByLimit(100).getOrThrow().size)
        }
    }

    @Test
    fun `executeDeleteExpired removes entries older than the age cap`() = runTest {
        with(db) {
            repeat(2) { repo.insert(entry()).getOrThrow() } // future expiry
            // now = 0 ⇒ nothing is time-expired; a future minCreatedAt ⇒ every row is "too old" ⇒ swept.
            val future = nowMillis() + 60_000
            val deleted = repo.executeDeleteExpired(now = 0, minCreatedAt = future).getOrThrow()
            assertEquals(2L, deleted)
        }
    }

    @Test
    fun `executeDeleteExpired keeps live entries`() = runTest {
        with(db) {
            repeat(3) { repo.insert(entry()).getOrThrow() }

            val deleted = repo.executeDeleteExpired(now = nowMillis(), minCreatedAt = 0).getOrThrow()
            assertEquals(0L, deleted)
            assertEquals(3, repo.findAllByLimit(100).getOrThrow().size)
        }
    }

    @Test
    fun `executeDeleteExpired on empty table succeeds and returns 0`() = runTest {
        with(db) {
            val deleted = repo.executeDeleteExpired(now = nowMillis(), minCreatedAt = 0).getOrThrow()
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
            assertEquals(original.relay!!.copies, fetched.envelope.relay!!.copies)
            assertEquals(original.relay!!.priority, fetched.envelope.relay!!.priority)
        }
    }

    // ── generated columns & dedup index ─────────────────────────────────────────

    @Test
    fun `message_id unique index rejects a duplicate replica`() = runTest {
        with(db) {
            val original = envelope()
            repo.insert(RelayEntry(envelope = original)).getOrThrow()
            // A second replica with the same messageId must not be stored twice (dedup).
            assertFails { repo.insert(RelayEntry(envelope = original.copy())).getOrThrow() }
        }
    }

    @Test
    fun `expires_at generated column reflects the envelope`() = runTest {
        with(db) {
            val expiry = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val e = envelope().let { it.copy(relay = it.relay!!.copy(expiresAt = expiry)) }
            repo.insert(RelayEntry(envelope = e)).getOrThrow()

            val fetched = repo.findAllByLimit(1).getOrThrow().first()
            assertEquals(expiry, fetched.expiresAt)
            assertEquals(expiry, fetched.envelope.relay!!.expiresAt)
        }
    }
}
