package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.generated.ContactEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.TrustLevel
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ContactEntryRepositoryTest {

    private lateinit var db: ISQLite
    private val repo = ContactEntryRepositoryImpl

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

    private fun contact(name: String? = null): Contact {
        val sig = CryptoProvider.generateEd25519KeyPair()
        val enc = CryptoProvider.generateX25519KeyPair()
        return Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(sig.publicKey),
            encKey = Base64.encode(enc.publicKey),
            // Millisecond precision: the InstantConverter stores epoch millis, so a now() value with
            // finer precision would not round-trip exactly through the ContactConverter.
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            name = name,
        )
    }

    private fun entry(c: Contact = contact()) = ContactEntry(peerId = c.peerId, contact = c)

    @Test
    fun `insert assigns auto-increment id`() = runTest {
        with(db) {
            val saved = repo.insert(entry()).getOrThrow()
            assertTrue(saved.id > 0)
        }
    }

    @Test
    fun `findOneByPeerId returns the inserted row and round-trips the contact`() = runTest {
        with(db) {
            val c = contact(name = "Alice")
            val inserted = repo.insert(
                entry(c).copy(trustLevel = TrustLevel.KNOWN),
            ).getOrThrow()

            val found = assertNotNull(repo.findOneByPeerId(c.peerId).getOrThrow())
            assertEquals(inserted.id, found.id)
            assertEquals(c.peerId, found.peerId)
            assertEquals(TrustLevel.KNOWN, found.trustLevel)
            // ContactConverter round-trip.
            assertEquals(c, found.contact)
            assertEquals(c.sigKey, found.contact.sigKey)
            assertEquals(c.peerId, found.contact.peerId)
            assertEquals("Alice", found.contact.name)
        }
    }

    @Test
    fun `findOneByPeerId returns null for an unknown peer`() = runTest {
        with(db) {
            assertNull(repo.findOneByPeerId(contact().peerId).getOrThrow())
        }
    }

    @Test
    fun `findAll returns all inserted rows`() = runTest {
        with(db) {
            val a = repo.insert(entry()).getOrThrow()
            val b = repo.insert(entry()).getOrThrow()
            val c = repo.insert(entry()).getOrThrow()

            val all = repo.findAll().getOrThrow()
            assertEquals(3, all.size)
            assertEquals(
                setOf(a.peerId, b.peerId, c.peerId),
                all.map { it.peerId }.toSet(),
            )
        }
    }

    @Test
    fun `peer_id unique index rejects a duplicate`() = runTest {
        with(db) {
            val c = contact()
            repo.insert(entry(c)).getOrThrow()
            assertFails { repo.insert(entry(c)).getOrThrow() }
        }
    }

    @Test
    fun `update persists a changed field`() = runTest {
        with(db) {
            val c = contact()
            val inserted = repo.insert(entry(c)).getOrThrow()
            val updated = repo.update(
                inserted.copy(name = "Local Name", trustLevel = TrustLevel.KNOWN),
            ).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals("Local Name", updated.name)

            val refetched = assertNotNull(repo.findOneByPeerId(c.peerId).getOrThrow())
            assertEquals("Local Name", refetched.name)
            assertEquals(TrustLevel.KNOWN, refetched.trustLevel)
        }
    }

    @Test
    fun `save with existing id updates in place`() = runTest {
        with(db) {
            val c = contact()
            val inserted = repo.insert(entry(c)).getOrThrow()
            repo.save(inserted.copy(name = "Renamed")).getOrThrow()

            val all = repo.findAll().getOrThrow()
            assertEquals(1, all.size)
            assertEquals("Renamed", assertNotNull(repo.findOneByPeerId(c.peerId).getOrThrow()).name)
        }
    }

    @Test
    fun `save with id zero inserts`() = runTest {
        with(db) {
            val c = contact()
            val saved = repo.save(entry(c)).getOrThrow()
            assertTrue(saved.id > 0)

            val refetched = assertNotNull(repo.findOneByPeerId(c.peerId).getOrThrow())
            assertEquals(saved.id, refetched.id)
        }
    }

    @Test
    fun `delete removes only the targeted entry`() = runTest {
        with(db) {
            val a = repo.insert(entry()).getOrThrow()
            val b = repo.insert(entry()).getOrThrow()

            repo.delete(a).getOrThrow()

            assertNull(repo.findOneByPeerId(a.peerId).getOrThrow())
            assertNotNull(repo.findOneByPeerId(b.peerId).getOrThrow())
        }
    }

    @Test
    fun `delete fails when no row matches`() = runTest {
        with(db) {
            val inserted = repo.insert(entry()).getOrThrow()
            repo.delete(inserted).getOrThrow()
            // The row is already gone, so a second delete affects zero rows.
            assertFails { repo.delete(inserted).getOrThrow() }
        }
    }

    @Test
    fun `deleteAll clears the table`() = runTest {
        with(db) {
            repo.insert(entry()).getOrThrow()
            repo.insert(entry()).getOrThrow()

            repo.deleteAll().getOrThrow()

            assertTrue(repo.findAll().getOrThrow().isEmpty())
        }
    }

    @Test
    fun `batchInsert persists every entry`() = runTest {
        with(db) {
            val entries = List(3) { entry() }
            repo.batchInsert(entries).getOrThrow()

            val all = repo.findAll().getOrThrow()
            assertEquals(3, all.size)
            assertEquals(
                entries.map { it.peerId }.toSet(),
                all.map { it.peerId }.toSet(),
            )
        }
    }
}
