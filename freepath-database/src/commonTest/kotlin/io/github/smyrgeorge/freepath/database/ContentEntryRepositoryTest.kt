package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.generated.ContentEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.model.contact.ContactCodec
import io.github.smyrgeorge.freepath.model.content.Content
import io.github.smyrgeorge.freepath.model.content.ContentBody
import io.github.smyrgeorge.freepath.model.content.ContentCodec
import io.github.smyrgeorge.freepath.model.content.ContentType
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentEntryRepositoryTest {

    private lateinit var db: ISQLite
    private val repo = ContentEntryRepositoryImpl

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val kp = CryptoProvider.generateEd25519KeyPair()
    private val authorId = ContactCodec.derivePeerId(kp.publicKey)

    private fun article(title: String = "Hello", bodyText: String = "World"): Content =
        ContentCodec.seal(
            body = ContentBody.Article(title = title, body = bodyText),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )

    private fun contactContent(bio: String? = "bio"): Content =
        ContentCodec.seal(
            body = ContentBody.Contact(bio = bio, avatar = null, location = null),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )

    private fun otherAuthor(): Pair<Content, String> {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val authorId = ContactCodec.derivePeerId(kp.publicKey)
        val content = ContentCodec.seal(
            body = ContentBody.Article(title = "Other", body = "Author"),
            authorId = authorId,
            sigKeyPrivate = kp.privateKey,
        )
        return content to authorId
    }

    // ── insert / generated columns ──────────────────────────────────────────────

    @Test
    fun `insert assigns id`() = runTest {
        with(db) {
            val saved = repo.insert(ContentEntry.from(article())).getOrThrow()
            assertTrue(saved.id > 0, "Expected auto-assigned id > 0, got ${saved.id}")
        }
    }

    @Test
    fun `insert populates the generated columns`() = runTest {
        with(db) {
            val content = article()
            repo.insert(ContentEntry.from(content)).getOrThrow()

            val fetched = assertNotNull(repo.findOneByContentId(content.id).getOrThrow())
            assertEquals(content.id, fetched.contentId)
            assertEquals(content.type, fetched.type)
            assertEquals(content.authorId, fetched.authorId)
            assertEquals(content.version, fetched.version)
            assertEquals(content.signature, fetched.signature)
        }
    }

    @Test
    fun `ContentConverter round-trips the content`() = runTest {
        with(db) {
            val content = article()
            repo.insert(ContentEntry.from(content)).getOrThrow()

            val fetched = assertNotNull(repo.findOneByContentId(content.id).getOrThrow())
            // The InstantConverter stores epoch millis, so createdAt is compared at millisecond
            // precision; every other field must round-trip exactly.
            assertEquals(content.id, fetched.content.id)
            assertEquals(content.authorId, fetched.content.authorId)
            assertEquals(content.type, fetched.content.type)
            assertEquals(content.version, fetched.content.version)
            assertEquals(content.signature, fetched.content.signature)
            assertEquals(content.body, fetched.content.body)
            assertEquals(content.createdAt.toEpochMilliseconds(), fetched.content.createdAt.toEpochMilliseconds())
        }
    }

    // ── findOneByContentId ──────────────────────────────────────────────────────

    @Test
    fun `findOneByContentId returns the matching row`() = runTest {
        with(db) {
            val content = article()
            val inserted = repo.insert(ContentEntry.from(content)).getOrThrow()

            val found = assertNotNull(repo.findOneByContentId(content.id).getOrThrow())
            assertEquals(inserted.id, found.id)
            assertEquals(content.id, found.contentId)
        }
    }

    @Test
    fun `findOneByContentId returns null for an unknown id`() = runTest {
        with(db) {
            repo.insert(ContentEntry.from(article())).getOrThrow()
            assertNull(repo.findOneByContentId("does-not-exist").getOrThrow())
        }
    }

    // ── findAllByLimitAndOffset (excludes CONTACT) ───────────────────────────────

    @Test
    fun `findAllByLimitAndOffset returns ARTICLE content but excludes CONTACT`() = runTest {
        with(db) {
            val art = article()
            repo.insert(ContentEntry.from(art)).getOrThrow()
            repo.insert(ContentEntry.from(contactContent())).getOrThrow()

            val result = repo.findAllByLimitAndOffset(limit = 100, offset = 0).getOrThrow()
            assertEquals(1, result.size)
            assertEquals(art.id, result.first().contentId)
            assertEquals(ContentType.ARTICLE, result.first().type)
        }
    }

    @Test
    fun `findAllByLimitAndOffset orders by id descending`() = runTest {
        with(db) {
            repo.insert(ContentEntry.from(article(title = "first"))).getOrThrow()
            repo.insert(ContentEntry.from(article(title = "second"))).getOrThrow()

            val result = repo.findAllByLimitAndOffset(limit = 100, offset = 0).getOrThrow()
            assertEquals(2, result.size)
            assertEquals(result.sortedByDescending { it.id }, result)
        }
    }

    // ── findOneByAuthorIdAndTypeContact ──────────────────────────────────────────

    @Test
    fun `findOneByAuthorIdAndTypeContact returns the CONTACT content`() = runTest {
        with(db) {
            val contact = contactContent(bio = "the-bio")
            repo.insert(ContentEntry.from(article())).getOrThrow()
            repo.insert(ContentEntry.from(contact)).getOrThrow()

            val found = assertNotNull(repo.findOneByAuthorIdAndTypeContact(authorId).getOrThrow())
            assertEquals(ContentType.CONTACT, found.type)
            assertEquals(contact.id, found.contentId)
        }
    }

    @Test
    fun `findOneByAuthorIdAndTypeContact returns null when the author has no CONTACT content`() = runTest {
        with(db) {
            repo.insert(ContentEntry.from(article())).getOrThrow()
            assertNull(repo.findOneByAuthorIdAndTypeContact(authorId).getOrThrow())
        }
    }

    // ── findAllByAuthorIdAndLimitAndOffset ───────────────────────────────────────

    @Test
    fun `findAllByAuthorIdAndLimitAndOffset filters by author`() = runTest {
        with(db) {
            val mine = article(title = "mine")
            repo.insert(ContentEntry.from(mine)).getOrThrow()
            val (theirs, otherAuthorId) = otherAuthor()
            repo.insert(ContentEntry.from(theirs)).getOrThrow()

            val mineResult = repo.findAllByAuthorIdAndLimitAndOffset(authorId, limit = 100, offset = 0).getOrThrow()
            assertEquals(1, mineResult.size)
            assertEquals(mine.id, mineResult.first().contentId)

            val theirsResult =
                repo.findAllByAuthorIdAndLimitAndOffset(otherAuthorId, limit = 100, offset = 0).getOrThrow()
            assertEquals(1, theirsResult.size)
            assertEquals(theirs.id, theirsResult.first().contentId)
        }
    }

    @Test
    fun `findAllByAuthorIdAndLimitAndOffset excludes CONTACT content`() = runTest {
        with(db) {
            repo.insert(ContentEntry.from(article())).getOrThrow()
            repo.insert(ContentEntry.from(contactContent())).getOrThrow()

            val result = repo.findAllByAuthorIdAndLimitAndOffset(authorId, limit = 100, offset = 0).getOrThrow()
            assertEquals(1, result.size)
            assertEquals(ContentType.ARTICLE, result.first().type)
        }
    }

    // ── content_id UNIQUE index ──────────────────────────────────────────────────

    @Test
    fun `content_id unique index rejects a duplicate`() = runTest {
        with(db) {
            val content = article()
            repo.insert(ContentEntry.from(content)).getOrThrow()
            // The same content has the same content_id ⇒ the unique index must reject it.
            assertFails { repo.insert(ContentEntry.from(content)).getOrThrow() }
        }
    }

    @Test
    fun `two different contents from the same author are both stored`() = runTest {
        with(db) {
            repo.insert(ContentEntry.from(article(title = "a"))).getOrThrow()
            repo.insert(ContentEntry.from(article(title = "b"))).getOrThrow()

            val result = repo.findAllByAuthorIdAndLimitAndOffset(authorId, limit = 100, offset = 0).getOrThrow()
            assertEquals(2, result.size)
        }
    }

    // ── update / save ────────────────────────────────────────────────────────────

    @Test
    fun `update changes the trust`() = runTest {
        with(db) {
            val inserted = repo.insert(ContentEntry.from(article())).getOrThrow()
            assertEquals(ContentTrust.UNKNOWN, inserted.trust)

            val updated = repo.update(inserted.copy(trust = ContentTrust.VERIFIED)).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals(ContentTrust.VERIFIED, updated.trust)

            val fetched = assertNotNull(repo.findOneByContentId(inserted.contentId).getOrThrow())
            assertEquals(ContentTrust.VERIFIED, fetched.trust)
        }
    }

    @Test
    fun `save with id=0 inserts a new entry`() = runTest {
        with(db) {
            val saved = repo.save(ContentEntry.from(article())).getOrThrow()
            assertTrue(saved.id > 0)
        }
    }

    @Test
    fun `save with an existing id updates the entry`() = runTest {
        with(db) {
            val inserted = repo.insert(ContentEntry.from(article())).getOrThrow()
            val updated = repo.save(inserted.copy(trust = ContentTrust.FAILED)).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals(ContentTrust.FAILED, updated.trust)
        }
    }

    // ── delete / deleteAll ───────────────────────────────────────────────────────

    @Test
    fun `delete removes the entry`() = runTest {
        with(db) {
            val content = article()
            val inserted = repo.insert(ContentEntry.from(content)).getOrThrow()
            repo.delete(inserted).getOrThrow()
            assertNull(repo.findOneByContentId(content.id).getOrThrow())
        }
    }

    @Test
    fun `delete fails when no rows match`() = runTest {
        with(db) {
            assertFails { repo.delete(ContentEntry.from(article())).getOrThrow() }
        }
    }

    @Test
    fun `deleteAll clears the table`() = runTest {
        with(db) {
            repo.insert(ContentEntry.from(article(title = "a"))).getOrThrow()
            repo.insert(ContentEntry.from(article(title = "b"))).getOrThrow()
            repo.insert(ContentEntry.from(contactContent())).getOrThrow()

            repo.deleteAll().getOrThrow()
            assertTrue(repo.findAllByLimitAndOffset(limit = 100, offset = 0).getOrThrow().isEmpty())
            assertNull(repo.findOneByAuthorIdAndTypeContact(authorId).getOrThrow())
        }
    }

    @Test
    fun `deleteAll on an empty table returns 0`() = runTest {
        with(db) {
            assertEquals(0L, repo.deleteAll().getOrThrow())
        }
    }
}
