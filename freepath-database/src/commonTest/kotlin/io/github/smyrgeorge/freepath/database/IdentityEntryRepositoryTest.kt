package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.generated.IdentityEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdentityEntryRepositoryTest {

    private lateinit var db: ISQLite
    private val repo = IdentityEntryRepositoryImpl

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

    private fun identity(): Identity {
        val sig = CryptoProvider.generateEd25519KeyPair()
        val enc = CryptoProvider.generateX25519KeyPair()
        return Identity(
            peerIdRaw = CryptoProvider.sha256(sig.publicKey),
            sigKeyPublic = sig.publicKey,
            sigKeyPrivate = sig.privateKey,
            encKeyPublic = enc.publicKey,
            encKeyPrivate = enc.privateKey,
        )
    }

    private fun entry(id: Identity = identity()) = IdentityEntry(peerId = id.peerId, identity = id)

    @Test
    fun `insert assigns auto-increment id`() = runTest {
        with(db) {
            val saved = repo.insert(entry()).getOrThrow()
            assertTrue(saved.id > 0)
        }
    }

    @Test
    fun `findAll returns the inserted rows`() = runTest {
        with(db) {
            val a = entry()
            val b = entry()
            repo.insert(a).getOrThrow()
            repo.insert(b).getOrThrow()

            val all = repo.findAll().getOrThrow()
            assertEquals(2, all.size)
            assertTrue(all.any { it.peerId == a.peerId })
            assertTrue(all.any { it.peerId == b.peerId })
        }
    }

    @Test
    fun `identity converter round-trips the identity`() = runTest {
        with(db) {
            val id = identity()
            repo.insert(entry(id)).getOrThrow()

            val found = assertNotNull(repo.findAll().getOrThrow().firstOrNull { it.peerId == id.peerId })
            assertEquals(id.peerId, found.identity.peerId)
            assertEquals(id, found.identity)
            assertTrue(id.sigKeyPublic.contentEquals(found.identity.sigKeyPublic))
            assertTrue(id.sigKeyPrivate.contentEquals(found.identity.sigKeyPrivate))
            assertTrue(id.encKeyPublic.contentEquals(found.identity.encKeyPublic))
            assertTrue(id.encKeyPrivate.contentEquals(found.identity.encKeyPrivate))
        }
    }

    @Test
    fun `peer_id unique index rejects a duplicate`() = runTest {
        with(db) {
            val id = identity()
            repo.insert(entry(id)).getOrThrow()
            assertFails { repo.insert(entry(id)).getOrThrow() }
        }
    }

    @Test
    fun `update replaces the stored identity`() = runTest {
        with(db) {
            val inserted = repo.insert(entry()).getOrThrow()
            val replacement = identity()
            val updated = repo.update(
                inserted.copy(peerId = replacement.peerId, identity = replacement),
            ).getOrThrow()
            assertEquals(inserted.id, updated.id)

            val found = assertNotNull(repo.findAll().getOrThrow().firstOrNull { it.id == inserted.id })
            assertEquals(replacement.peerId, found.peerId)
            assertEquals(replacement, found.identity)
        }
    }

    @Test
    fun `save with zero id inserts`() = runTest {
        with(db) {
            val saved = repo.save(entry()).getOrThrow()
            assertTrue(saved.id > 0)
            assertEquals(1, repo.findAll().getOrThrow().size)
        }
    }

    @Test
    fun `save with existing id updates in place`() = runTest {
        with(db) {
            val inserted = repo.insert(entry()).getOrThrow()
            val replacement = identity()
            repo.save(inserted.copy(peerId = replacement.peerId, identity = replacement)).getOrThrow()

            val all = repo.findAll().getOrThrow()
            assertEquals(1, all.size)
            assertEquals(replacement.peerId, all.first().peerId)
        }
    }

    @Test
    fun `delete removes only the targeted entry`() = runTest {
        with(db) {
            val a = repo.insert(entry()).getOrThrow()
            val b = repo.insert(entry()).getOrThrow()

            repo.delete(a).getOrThrow()

            val all = repo.findAll().getOrThrow()
            assertEquals(1, all.size)
            assertEquals(b.id, all.first().id)
        }
    }

    @Test
    fun `delete fails when the row does not exist`() = runTest {
        with(db) {
            assertFails { repo.delete(entry()).getOrThrow() }
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
}
