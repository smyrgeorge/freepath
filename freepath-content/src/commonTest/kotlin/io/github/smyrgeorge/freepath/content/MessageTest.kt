package io.github.smyrgeorge.freepath.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class MessageTest {

    // ── conversationId ────────────────────────────────────────────────────────

    @Test
    fun conversationId_isSymmetric() {
        val ab = Message.conversationId("alice", "bob")
        val ba = Message.conversationId("bob", "alice")
        assertEquals(ab, ba)
    }

    @Test
    fun conversationId_isDeterministic() {
        val one = Message.conversationId("x", "y")
        val two = Message.conversationId("x", "y")
        assertEquals(one, two)
    }

    @Test
    fun conversationId_differsForDifferentPeers() {
        val ab = Message.conversationId("alice", "bob")
        val ac = Message.conversationId("alice", "carol")
        assertNotEquals(ab, ac)
    }

    @Test
    fun conversationId_differsWhenPeerChangesEvenIfSortedSame() {
        // "aa","ab" sorts as aa,ab; "aa","ac" sorts as aa,ac — must differ
        val a = Message.conversationId("aa", "ab")
        val b = Message.conversationId("aa", "ac")
        assertNotEquals(a, b)
    }

    @Test
    fun conversationId_samePeerBothSides_isStable() {
        val a = Message.conversationId("self", "self")
        val b = Message.conversationId("self", "self")
        assertEquals(a, b)
    }

    // ── init validation ──────────────────────────────────────────────────────

    @Test
    fun rejectsEmptyId() {
        assertFails {
            Message(
                id = "",
                conversationId = Message.conversationId("a", "b"),
                senderId = "a",
                recipientId = "b",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1L),
                body = "hi",
            )
        }
    }

    @Test
    fun rejectsBlankId() {
        assertFails {
            Message(
                id = "   ",
                conversationId = Message.conversationId("a", "b"),
                senderId = "a",
                recipientId = "b",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1L),
                body = "hi",
            )
        }
    }

    @Test
    fun rejectsEmptyRecipientId() {
        assertFails {
            Message(
                id = "id",
                conversationId = Message.conversationId("a", ""),
                senderId = "a",
                recipientId = "",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1L),
                body = "hi",
            )
        }
    }

    @Test
    fun rejectsEmptyBody() {
        assertFails {
            Message(
                id = "id",
                conversationId = Message.conversationId("a", "b"),
                senderId = "a",
                recipientId = "b",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1L),
                body = "",
            )
        }
    }

    @Test
    fun rejectsMismatchedConversationId_forDirectMessage() {
        assertFails {
            Message(
                id = "id",
                conversationId = Message.conversationId("x", "y"),  // wrong peers
                senderId = "a",
                recipientId = "b",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1L),
                body = "hi",
            )
        }
    }

    @Test
    fun groupMessage_anyConversationIdAccepted() {
        // No recipientId -> validateConversationId returns true regardless
        val groupId = Message.conversationId("group", "any")
        val m = Message(
            id = "id",
            conversationId = groupId,
            senderId = "a",
            recipientId = null,
            signature = "sig",
            timestamp = Instant.fromEpochMilliseconds(1L),
            body = "hi",
        )
        assertEquals(groupId, m.conversationId)
    }

    @Test
    fun rejectsArticleContent() {
        assertFails {
            Message(
                id = "id",
                conversationId = Message.conversationId("a", "b"),
                senderId = "a",
                recipientId = "b",
                signature = "sig",
                timestamp = Instant.fromEpochMilliseconds(1L),
                content = ContentBody.Article("t", "b"),
            )
        }
    }

    @Test
    fun acceptsImageContent() {
        val m = Message(
            id = "id",
            conversationId = Message.conversationId("a", "b"),
            senderId = "a",
            recipientId = "b",
            signature = "sig",
            timestamp = Instant.fromEpochMilliseconds(1L),
            content = ContentBody.Image(
                data = "AAAA",
                format = ImageFormat.PNG,
                width = 1,
                height = 1,
                caption = null,
            ),
        )
        assertEquals(ContentType.IMAGE.name, m.content!!::class.simpleName?.uppercase())
    }
}
