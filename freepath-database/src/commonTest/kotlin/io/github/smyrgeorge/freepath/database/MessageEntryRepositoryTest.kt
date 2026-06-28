package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.generated.MessageEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.model.contact.ContactCodec
import io.github.smyrgeorge.freepath.model.content.Message
import io.github.smyrgeorge.freepath.model.content.MessageCodec
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MessageEntryRepositoryTest {

    private lateinit var db: ISQLite
    private val repo = MessageEntryRepositoryImpl

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
    private val alice = ContactCodec.derivePeerId(kp.publicKey)
    private val bob = "bob-peer"

    // MessageCodec.seal requires conversationId == conversationId(sender, recipient), so the
    // conversation is always derived from the recipient. Millisecond-precision timestamp: the
    // InstantConverter stores epoch millis, so a now() value would not round-trip exactly.
    private fun message(
        text: String = "hi",
        recipient: String = bob,
        timestamp: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
    ): Message =
        MessageCodec.seal(
            sigKeyPrivate = kp.privateKey,
            conversationId = Message.conversationId(alice, recipient),
            senderId = alice,
            recipientId = recipient,
            timestamp = timestamp,
            body = text,
        )

    // ── insert / generated columns ──────────────────────────────────────────────

    @Test
    fun `insert assigns auto-increment id`() = runTest {
        with(db) {
            val saved = repo.insert(MessageEntry.from(message(), MessageStatus.SENDING)).getOrThrow()
            assertTrue(saved.id > 0, "Expected auto-assigned id > 0, got ${saved.id}")
        }
    }

    @Test
    fun `insert populates the generated columns from the message json`() = runTest {
        with(db) {
            val message = message()
            val inserted = repo.insert(MessageEntry.from(message, MessageStatus.SENDING)).getOrThrow()

            val fetched = repo.findOneById(inserted.id).getOrThrow()!!
            assertEquals(message.id, fetched.messageId)
            assertEquals(message.conversationId.toString(), fetched.conversationId)
            assertEquals(message.senderId, fetched.senderId)
            assertEquals(message.recipientId, fetched.recipientId)
            assertEquals(message.signature, fetched.signature)
            assertEquals(message.timestamp, fetched.timestamp)
        }
    }

    // ── message converter roundtrip ─────────────────────────────────────────────

    @Test
    fun `stored message roundtrips through the converter`() = runTest {
        with(db) {
            val message = message(text = "hello mesh")
            val inserted = repo.insert(MessageEntry.from(message, MessageStatus.SENDING)).getOrThrow()

            val fetched = repo.findOneById(inserted.id).getOrThrow()!!
            assertEquals(message, fetched.message)
        }
    }

    // ── findAllByConversationId ─────────────────────────────────────────────────

    @Test
    fun `findAllByConversationId returns messages ordered by timestamp ascending`() = runTest {
        with(db) {
            val base = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val first = message(text = "first", timestamp = base)
            val second = message(text = "second", timestamp = base + 1.seconds)
            val third = message(text = "third", timestamp = base + 2.seconds)

            // Insert out of order to prove the query orders by timestamp.
            repo.insert(MessageEntry.from(third, MessageStatus.SENT)).getOrThrow()
            repo.insert(MessageEntry.from(first, MessageStatus.SENT)).getOrThrow()
            repo.insert(MessageEntry.from(second, MessageStatus.SENT)).getOrThrow()

            val conv = Message.conversationId(alice, bob)
            val result = repo.findAllByConversationId(conv, limit = 100, offset = 0).getOrThrow()
            assertEquals(listOf(first.id, second.id, third.id), result.map { it.messageId })
        }
    }

    @Test
    fun `findAllByConversationId excludes messages from other conversations`() = runTest {
        with(db) {
            val mine = message(text = "for-bob", recipient = bob)
            val other = message(text = "for-carol", recipient = "carol-peer")

            repo.insert(MessageEntry.from(mine, MessageStatus.SENT)).getOrThrow()
            repo.insert(MessageEntry.from(other, MessageStatus.SENT)).getOrThrow()

            val convA = Message.conversationId(alice, bob)
            val result = repo.findAllByConversationId(convA, limit = 100, offset = 0).getOrThrow()
            assertEquals(1, result.size)
            assertEquals(mine.id, result.first().messageId)
        }
    }

    // ── message_id unique index ─────────────────────────────────────────────────

    @Test
    fun `message_id unique index rejects a duplicate message`() = runTest {
        with(db) {
            val message = message()
            repo.insert(MessageEntry.from(message, MessageStatus.SENDING)).getOrThrow()
            // The same message inserted again must be rejected by the unique index.
            assertFails { repo.insert(MessageEntry.from(message, MessageStatus.SENDING)).getOrThrow() }
        }
    }

    // ── update ──────────────────────────────────────────────────────────────────

    @Test
    fun `update persists a changed status`() = runTest {
        with(db) {
            val inserted = repo.insert(MessageEntry.from(message(), MessageStatus.SENDING)).getOrThrow()
            val updated = repo.update(inserted.copy(status = MessageStatus.SENT)).getOrThrow()
            assertEquals(inserted.id, updated.id)
            assertEquals(MessageStatus.SENT, updated.status)

            val fetched = repo.findOneById(inserted.id).getOrThrow()!!
            assertEquals(MessageStatus.SENT, fetched.status)
        }
    }

    // ── deleteAll ───────────────────────────────────────────────────────────────

    @Test
    fun `deleteAll removes every message in the table`() = runTest {
        with(db) {
            repo.insert(MessageEntry.from(message(text = "a"), MessageStatus.SENT)).getOrThrow()
            repo.insert(MessageEntry.from(message(text = "b"), MessageStatus.SENT)).getOrThrow()

            repo.deleteAll().getOrThrow()

            val conv = Message.conversationId(alice, bob)
            assertTrue(repo.findAllByConversationId(conv, limit = 100, offset = 0).getOrThrow().isEmpty())
        }
    }
}
