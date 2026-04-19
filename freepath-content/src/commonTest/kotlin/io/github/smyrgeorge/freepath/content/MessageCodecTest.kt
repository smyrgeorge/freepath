package io.github.smyrgeorge.freepath.content

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.crypto.KeyPair
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class MessageCodecTest {

    // ── Message validation ─────────────────────────────────────────────────────

    @Test
    fun message_rejectsBlankSenderId() {
        assertFails {
            Message(
                id = "id",
                conversationId = Message.conversationId("  ", "recipient"),
                senderId = "  ",
                recipientId = "recipient",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1000L),
                body = "hello",
            )
        }
    }

    @Test
    fun message_rejectsBlankRecipientIdWhenProvided() {
        assertFails {
            Message(
                id = "id",
                conversationId = Message.conversationId("sender", "  "),
                senderId = "sender",
                recipientId = "  ",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1000L),
                body = "hello",
            )
        }
    }

    @Test
    fun message_rejectsBlankBody() {
        assertFails {
            Message(
                id = "id",
                conversationId = Message.conversationId("sender", "recipient"),
                senderId = "sender",
                recipientId = "recipient",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1000L),
                body = "   ",
            )
        }
    }

    @Test
    fun message_rejectsArticleContent() {
        assertFails {
            Message(
                id = "id",
                conversationId = Message.conversationId("sender", "recipient"),
                senderId = "sender",
                recipientId = "recipient",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1000L),
                content = ContentBody.Article("title", "body"),
            )
        }
    }

    @Test
    fun message_rejectsWrongSchema() {
        assertFails {
            Message(
                id = "id",
                schema = 999,
                conversationId = Message.conversationId("sender", "recipient"),
                senderId = "sender",
                recipientId = "recipient",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1000L),
                body = "hello",
            )
        }
    }

    @Test
    fun message_acceptsDirectMessage() {
        val msg = Message(
            id = "id",
            conversationId = Message.conversationId("sender", "recipient"),
            senderId = "sender",
            recipientId = "recipient",
            signature = "sig",
            timestamp = Instant.fromEpochMilliseconds(1000L),
            body = "hello",
        )
        assertEquals("sender", msg.senderId)
        assertEquals("recipient", msg.recipientId)
    }

    @Test
    fun message_acceptsGroupMessage() {
        val groupId = Uuid.random()
        val msg = Message(
            id = "id",
            conversationId = groupId,
            senderId = "sender",
            signature = "sig",
            timestamp = Instant.fromEpochMilliseconds(1000L),
            body = "hello",
        )
        assertEquals(groupId, msg.conversationId)
        assertEquals(null, msg.recipientId)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun makeKeys(): Pair<KeyPair, String> {
        val kp = CryptoProvider.generateEd25519KeyPair()
        return Pair(kp, Base64.encode(kp.publicKey))
    }

    private fun sealDirect(
        kp: KeyPair,
        senderId: String = "sender",
        recipientId: String = "recipient",
        body: String = "hello",
    ) = MessageCodec.seal(
        sigKeyPrivate = kp.privateKey,
        conversationId = Message.conversationId(senderId, recipientId),
        senderId = senderId,
        recipientId = recipientId,
        body = body,
    )

    private fun sealGroup(
        kp: KeyPair,
        groupId: Uuid = Uuid.random(),
        senderId: String = "sender",
        body: String = "hello",
    ) = MessageCodec.seal(
        sigKeyPrivate = kp.privateKey,
        conversationId = groupId,
        senderId = senderId,
        body = body,
    )

    // ── seal / verify — direct message ────────────────────────────────────────

    @Test
    fun seal_direct_producesVerifiableMessage() {
        val (kp, _) = makeKeys()
        val msg = sealDirect(kp)
        assertTrue(MessageCodec.verify(msg, kp.publicKey))
    }

    @Test
    fun seal_direct_verifyByBase64StringKey() {
        val (kp, pubKeyB64) = makeKeys()
        val msg = sealDirect(kp)
        assertTrue(MessageCodec.verify(msg, pubKeyB64))
    }

    @Test
    fun seal_direct_setsExpectedFields() {
        val (kp, _) = makeKeys()
        val msg = sealDirect(kp, senderId = "alice", recipientId = "bob", body = "hi")
        assertEquals(Message.SCHEMA, msg.schema)
        assertEquals("alice", msg.senderId)
        assertEquals("bob", msg.recipientId)
        assertEquals("hi", msg.body)
        assertEquals(Message.conversationId("alice", "bob"), msg.conversationId)
        assertTrue(msg.signature.isNotBlank())
    }

    @Test
    fun seal_direct_idMatchesDeriveId() {
        val (kp, _) = makeKeys()
        val msg = sealDirect(kp, senderId = "alice", recipientId = "bob")
        val expected = MessageCodec.deriveId(
            conversationId = msg.conversationId,
            timestamp = msg.timestamp,
            body = msg.body,
            content = msg.content,
        )
        assertEquals(expected, msg.id)
    }

    // ── seal / verify — group message ─────────────────────────────────────────

    @Test
    fun seal_group_producesVerifiableMessage() {
        val (kp, _) = makeKeys()
        val msg = sealGroup(kp)
        assertTrue(MessageCodec.verify(msg, kp.publicKey))
    }

    @Test
    fun seal_group_setsExpectedFields() {
        val (kp, _) = makeKeys()
        val groupId = Uuid.random()
        val msg = sealGroup(kp, groupId = groupId, senderId = "alice", body = "hey group")
        assertEquals(Message.SCHEMA, msg.schema)
        assertEquals(groupId, msg.conversationId)
        assertEquals("alice", msg.senderId)
        assertEquals(null, msg.recipientId)
        assertEquals("hey group", msg.body)
        assertTrue(msg.signature.isNotBlank())
    }

    @Test
    fun seal_group_idMatchesDeriveId() {
        val (kp, _) = makeKeys()
        val groupId = Uuid.random()
        val msg = sealGroup(kp, groupId = groupId, senderId = "alice")
        val expected = MessageCodec.deriveId(
            conversationId = groupId,
            timestamp = msg.timestamp,
            body = msg.body,
            content = msg.content,
        )
        assertEquals(expected, msg.id)
    }

    // ── seal with ContentBody ─────────────────────────────────────────────────

    @Test
    fun seal_withImageContent_producesVerifiableMessage() {
        val (kp, _) = makeKeys()
        val image = ContentBody.Image(
            data = "aGVsbG8=",
            format = ImageFormat.PNG,
            width = 64,
            height = 64,
            caption = null,
        )
        val msg = MessageCodec.seal(
            sigKeyPrivate = kp.privateKey,
            conversationId = Message.conversationId("sender", "recipient"),
            senderId = "sender",
            recipientId = "recipient",
            content = image,
        )
        assertTrue(MessageCodec.verify(msg, kp.publicKey))
        assertEquals(image, msg.content)
    }

    // ── verify — tamper detection ──────────────────────────────────────────────

    @Test
    fun verify_failsForTamperedBody() {
        val (kp, _) = makeKeys()
        val msg = sealDirect(kp, body = "original")
        val tampered = msg.copy(body = "tampered")
        assertFalse(MessageCodec.verify(tampered, kp.publicKey))
    }

    @Test
    fun verify_failsForTamperedSenderId() {
        val (kp, _) = makeKeys()
        val msg = sealDirect(kp, senderId = "alice", recipientId = "bob")
        // Tamper requires rebuilding conversationId so Message init passes;
        // signature is still bound to the original triplet, so verify must fail.
        val tampered = msg.copy(
            senderId = "eve",
            conversationId = Message.conversationId("eve", "bob"),
        )
        assertFalse(MessageCodec.verify(tampered, kp.publicKey))
    }

    @Test
    fun verify_failsForTamperedRecipientId() {
        val (kp, _) = makeKeys()
        val msg = sealDirect(kp, senderId = "alice", recipientId = "bob")
        val tampered = msg.copy(
            recipientId = "eve",
            conversationId = Message.conversationId("alice", "eve"),
        )
        assertFalse(MessageCodec.verify(tampered, kp.publicKey))
    }

    @Test
    fun verify_failsForWrongKey() {
        val (kp, _) = makeKeys()
        val (otherKp, _) = makeKeys()
        val msg = sealDirect(kp)
        assertFalse(MessageCodec.verify(msg, otherKp.publicKey))
    }

    // ── deriveId ──────────────────────────────────────────────────────────────

    @Test
    fun deriveId_isDeterministic() {
        val ts = Instant.fromEpochMilliseconds(1000L)
        val conversationId = Message.conversationId("sender", "recipient")
        val id1 = MessageCodec.deriveId(conversationId, ts, "hello", null)
        val id2 = MessageCodec.deriveId(conversationId, ts, "hello", null)
        assertEquals(id1, id2)
    }

    @Test
    fun deriveId_differsForDifferentBody() {
        val ts = Instant.fromEpochMilliseconds(1000L)
        val conversationId = Message.conversationId("sender", "recipient")
        val id1 = MessageCodec.deriveId(conversationId, ts, "hello", null)
        val id2 = MessageCodec.deriveId(conversationId, ts, "world", null)
        assertTrue(id1 != id2)
    }

    @Test
    fun deriveId_differsForDifferentConversationId() {
        val ts = Instant.fromEpochMilliseconds(1000L)
        val id1 = MessageCodec.deriveId(Uuid.random(), ts, "hello", null)
        val id2 = MessageCodec.deriveId(Uuid.random(), ts, "hello", null)
        assertTrue(id1 != id2)
    }

    @Test
    fun deriveId_isBase58() {
        val ts = Instant.fromEpochMilliseconds(1000L)
        val id = MessageCodec.deriveId(Message.conversationId("sender", "recipient"), ts, "hello", null)
        assertTrue(id.all { it in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz" })
        assertTrue(id.isNotEmpty())
    }

    // ── encode / decode ───────────────────────────────────────────────────────

    @Test
    fun encodeDecodeRoundTrip_direct() {
        val (kp, _) = makeKeys()
        val msg = sealDirect(kp)
        val decoded = MessageCodec.decode(MessageCodec.encode(msg)).getOrThrow()
        assertEquals(
            msg.copy(timestamp = Instant.fromEpochMilliseconds(msg.timestamp.toEpochMilliseconds())),
            decoded,
        )
    }

    @Test
    fun encodeDecodeRoundTrip_group() {
        val (kp, _) = makeKeys()
        val msg = sealGroup(kp)
        val decoded = MessageCodec.decode(MessageCodec.encode(msg)).getOrThrow()
        assertEquals(
            msg.copy(timestamp = Instant.fromEpochMilliseconds(msg.timestamp.toEpochMilliseconds())),
            decoded,
        )
    }

    @Test
    fun decode_failsForInvalidBytes() {
        val result = MessageCodec.decode("not protobuf".encodeToByteArray())
        assertTrue(result.isFailure)
    }

    @Test
    fun decode_succeedsButVerifyFailsForTamperedSignature() {
        val (kp, _) = makeKeys()
        val msg = sealDirect(kp)
        val tampered = msg.copy(
            signature = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        )
        val result = MessageCodec.decode(MessageCodec.encode(tampered))
        assertTrue(result.isSuccess)
        assertFalse(MessageCodec.verify(result.getOrThrow(), kp.publicKey))
    }
}
