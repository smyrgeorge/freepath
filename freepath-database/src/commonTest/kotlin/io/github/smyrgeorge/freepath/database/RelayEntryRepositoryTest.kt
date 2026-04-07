package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.ContactCodec
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.database.generated.RelayEntryRepositoryImpl
import kotlin.io.encoding.Base64
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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
        repo.deleteAll(db).getOrThrow()
    }

    @AfterTest
    fun tearDown() = runTest {
        db.close().getOrThrow()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun entry(
        peerId: String = "peer-alice",
        expireAt: Instant = Clock.System.now() + 30.days,
    ) = RelayEntry(
        receiverPeerId = peerId,
        payload = byteArrayOf(1, 2, 3, 4),
        expireAt = expireAt,
    )

    // ── insert ────────────────────────────────────────────────────────────────

    @Test
    fun `insert assigns auto-increment id`() = runTest {
        val saved = repo.insert(db, entry()).getOrThrow()
        assertTrue(saved.id > 0, "Expected auto-assigned id > 0, got ${saved.id}")
    }

    @Test
    fun `insert persists all fields`() = runTest {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        repo.insert(
            db, RelayEntry(
                receiverPeerId = "peer-bob",
                payload = payload,
                expireAt = Clock.System.now() + 7.days,
            )
        ).getOrThrow()

        val fetched = repo.findAllByLimit(db, 100).getOrThrow().first()
        assertEquals("peer-bob", fetched.receiverPeerId)
        assertTrue(fetched.payload.contentEquals(payload))
    }

    @Test
    fun `insert persists protobuf-encoded object as payload`() = runTest {
        val contact = Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(CryptoProvider.generateEd25519KeyPair().publicKey),
            encKey = Base64.encode(CryptoProvider.generateX25519KeyPair().publicKey),
            name = "Alice",
        )
        val payload = ContactCodec.encode(contact)
        repo.insert(db, RelayEntry(receiverPeerId = "peer-proto", payload = payload)).getOrThrow()

        val fetched = repo.findAllByLimit(db, 100).getOrThrow().first()
        val decoded = ContactCodec.decode(fetched.payload)
        assertEquals(contact.sigKey, decoded.sigKey)
        assertEquals(contact.encKey, decoded.encKey)
        assertEquals(contact.name, decoded.name)
    }

    @Test
    fun `insert persists large payload`() = runTest {
        val payload = ("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor " +
                "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud " +
                "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure " +
                "dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. " +
                "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt " +
                "mollit anim id est laborum.").encodeToByteArray()
        repo.insert(db, RelayEntry(receiverPeerId = "peer-lorem", payload = payload)).getOrThrow()

        val fetched = repo.findAllByLimit(db, 100).getOrThrow().first()
        assertTrue(fetched.payload.contentEquals(payload))
        assertEquals(payload.size, fetched.payload.size)
    }

    @Test
    fun `insert ids are unique and increasing`() = runTest {
        val a = repo.insert(db, entry()).getOrThrow()
        val b = repo.insert(db, entry()).getOrThrow()
        assertTrue(b.id > a.id)
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update persists changed receiverPeerId`() = runTest {
        val inserted = repo.insert(db, entry("peer-a")).getOrThrow()
        val updated = repo.update(db, inserted.copy(receiverPeerId = "peer-b")).getOrThrow()
        assertEquals(inserted.id, updated.id)
        assertEquals("peer-b", updated.receiverPeerId)
    }

    @Test
    fun `update sets updatedAt to a more recent timestamp`() = runTest {
        val inserted = repo.insert(db, entry()).getOrThrow()
        val updated = repo.update(db, inserted.copy(receiverPeerId = "peer-updated")).getOrThrow()
        assertTrue(updated.updatedAt >= inserted.updatedAt)
    }

    // ── save ─────────────────────────────────────────────────────────────────

    @Test
    fun `save with id=0 inserts new entry`() = runTest {
        val saved = repo.save(db, entry()).getOrThrow()
        assertTrue(saved.id > 0)
    }

    @Test
    fun `save with existing id updates entry`() = runTest {
        val inserted = repo.insert(db, entry("peer-original")).getOrThrow()
        val updated = repo.save(db, inserted.copy(receiverPeerId = "peer-modified")).getOrThrow()
        assertEquals(inserted.id, updated.id)
        assertEquals("peer-modified", updated.receiverPeerId)
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes the entry from the table`() = runTest {
        val inserted = repo.insert(db, entry()).getOrThrow()
        repo.delete(db, inserted).getOrThrow()
        val remaining = repo.findAllByLimit(db, 100).getOrThrow()
        assertTrue(remaining.isEmpty())
    }

    // ── batchInsert ───────────────────────────────────────────────────────────

    @Test
    fun `batchInsert assigns ids to all entries`() = runTest {
        val saved = repo.batchInsert(db, listOf(entry("p1"), entry("p2"), entry("p3"))).getOrThrow()
        assertEquals(3, saved.size)
        assertTrue(saved.all { it.id > 0 })
        assertEquals(setOf("p1", "p2", "p3"), saved.map { it.receiverPeerId }.toSet())
    }

    @Test
    fun `batchInsert with empty list returns empty result`() = runTest {
        val saved = repo.batchInsert(db, emptyList()).getOrThrow()
        assertTrue(saved.isEmpty())
    }

    // ── batchUpdate ───────────────────────────────────────────────────────────

    @Test
    fun `batchUpdate persists changed fields for all entries`() = runTest {
        val inserted = repo.batchInsert(db, listOf(entry("p1"), entry("p2"))).getOrThrow()
        val modified = inserted.map { it.copy(receiverPeerId = it.receiverPeerId + "-v2") }
        val updated = repo.batchUpdate(db, modified).getOrThrow()
        assertEquals(2, updated.size)
        assertTrue(updated.all { it.receiverPeerId.endsWith("-v2") })
    }

    @Test
    fun `batchUpdate with empty list returns empty result`() = runTest {
        val updated = repo.batchUpdate(db, emptyList()).getOrThrow()
        assertTrue(updated.isEmpty())
    }

    // ── findAllByLimit ────────────────────────────────────────────────────────

    @Test
    fun `findAllByLimit returns all non-expired entries`() = runTest {
        repo.insert(db, entry("alice")).getOrThrow()
        repo.insert(db, entry("alice")).getOrThrow()
        repo.insert(db, entry("bob")).getOrThrow()

        val result = repo.findAllByLimit(db, 100).getOrThrow()
        assertEquals(3, result.size)
    }

    @Test
    fun `findAllByLimit excludes expired entries`() = runTest {
        val past = Clock.System.now() - 1.hours
        repo.insert(db, entry("alice", expireAt = past)).getOrThrow()  // expired
        repo.insert(db, entry("alice")).getOrThrow()                    // valid
        repo.insert(db, entry("bob")).getOrThrow()                      // valid

        val result = repo.findAllByLimit(db, 100).getOrThrow()
        assertEquals(2, result.size)
    }

    @Test
    fun `findAllByLimit respects limit`() = runTest {
        repeat(10) { repo.insert(db, entry()).getOrThrow() }

        val result = repo.findAllByLimit(db, 3).getOrThrow()
        assertEquals(3, result.size)
    }

    @Test
    fun `findAllByLimit returns entries ordered by id ascending`() = runTest {
        repeat(3) { repo.insert(db, entry()).getOrThrow() }

        val result = repo.findAllByLimit(db, 100).getOrThrow()
        assertEquals(result.sortedBy { it.id }, result)
    }

    @Test
    fun `findAllByLimit returns empty list when table is empty`() = runTest {
        val result = repo.findAllByLimit(db, 100).getOrThrow()
        assertTrue(result.isEmpty())
    }

    // ── deleteById ────────────────────────────────────────────────────────────

    @Test
    fun `deleteById removes only the entry with that id`() = runTest {
        val a = repo.insert(db, entry("alice")).getOrThrow()
        val b = repo.insert(db, entry("alice")).getOrThrow()

        repo.deleteById(db, a.id).getOrThrow()

        val remaining = repo.findAllByLimit(db, 100).getOrThrow()
        assertEquals(1, remaining.size)
        assertEquals(b.id, remaining.first().id)
    }

    // ── deleteAll ─────────────────────────────────────────────────────────────

    @Test
    fun `deleteAll removes every entry in the table`() = runTest {
        repeat(5) { repo.insert(db, entry()).getOrThrow() }
        repo.deleteAll(db).getOrThrow()

        val remaining = repo.findAllByLimit(db, 100).getOrThrow()
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `deleteAll on empty table succeeds and returns 0`() = runTest {
        val deleted = repo.deleteAll(db).getOrThrow()
        assertEquals(0L, deleted)
    }

    // ── deleteExpired ─────────────────────────────────────────────────────────

    @Test
    fun `deleteExpired removes entries with expire_at in the past`() = runTest {
        val past = Clock.System.now() - 1.hours
        repo.insert(db, entry("alice", expireAt = past)).getOrThrow()
        repo.insert(db, entry("bob", expireAt = past)).getOrThrow()
        repo.insert(db, entry("carol")).getOrThrow()  // valid

        val deleted = repo.executeDeleteExpired(db).getOrThrow()
        assertEquals(2L, deleted)

        val remaining = repo.findAllByLimit(db, 100).getOrThrow()
        assertEquals(1, remaining.size)
        assertEquals("carol", remaining.first().receiverPeerId)
    }

    @Test
    fun `deleteExpired does not remove valid entries`() = runTest {
        repeat(3) { repo.insert(db, entry()).getOrThrow() }

        val deleted = repo.executeDeleteExpired(db).getOrThrow()
        assertEquals(0L, deleted)

        val remaining = repo.findAllByLimit(db, 100).getOrThrow()
        assertEquals(3, remaining.size)
    }

    @Test
    fun `deleteExpired on empty table succeeds and returns 0`() = runTest {
        val deleted = repo.executeDeleteExpired(db).getOrThrow()
        assertEquals(0L, deleted)
    }
}
