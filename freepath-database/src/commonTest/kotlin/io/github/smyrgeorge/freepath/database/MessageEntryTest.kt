package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.model.contact.ContactCodec
import io.github.smyrgeorge.freepath.model.content.Message
import io.github.smyrgeorge.freepath.model.content.MessageCodec
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MessageEntryTest {

    private val kp = CryptoProvider.generateEd25519KeyPair()
    private val alice = ContactCodec.derivePeerId(kp.publicKey)
    private val bob = "bob-peer"

    private fun message(text: String = "hi", conv: Uuid = Message.conversationId(alice, bob)): Message =
        MessageCodec.seal(
            sigKeyPrivate = kp.privateKey,
            conversationId = conv,
            senderId = alice,
            recipientId = bob,
            body = text,
        )

    @Test
    fun `from copies the derived fields off the message`() {
        val message = message()
        val entry = MessageEntry.from(message, MessageStatus.SENDING)

        assertEquals(message.id, entry.messageId)
        assertEquals(message.conversationId.toString(), entry.conversationId)
        assertEquals(message.senderId, entry.senderId)
        assertEquals(message.recipientId, entry.recipientId)
        assertEquals(message.signature, entry.signature)
        assertEquals(message.timestamp, entry.timestamp)
    }

    @Test
    fun `from carries the given status and message`() {
        val message = message()
        val entry = MessageEntry.from(message, MessageStatus.SENT)

        assertEquals(MessageStatus.SENT, entry.status)
        assertEquals(message, entry.message)
    }

    @Test
    fun `from defaults to a zero id`() {
        val entry = MessageEntry.from(message(), MessageStatus.SENDING)
        assertEquals(0, entry.id)
    }
}
