package io.github.smyrgeorge.freepath.content

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.serializer.InstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalSerializationApi::class, ExperimentalUuidApi::class)
@Serializable
data class Message(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val schema: Int = SCHEMA,
    @ProtoNumber(3) val conversationId: Uuid,
    @ProtoNumber(4) val senderId: String,
    @ProtoNumber(5) val recipientId: String? = null,
    @ProtoNumber(6) val signature: String,
    @ProtoNumber(7) @Serializable(with = InstantSerializer::class) val timestamp: Instant,
    @ProtoNumber(8) val body: String? = null,
    @ProtoNumber(9) val content: ContentBody? = null,
) {
    init {
        require(schema == SCHEMA) { "Unsupported schema version: $schema (expected $SCHEMA)" }
        require(id.isNotEmpty()) { "message id cannot be empty" }
        require(id.isNotBlank()) { "message id cannot be blank" }
        require(senderId.isNotBlank()) { "sender id cannot be blank" }
        recipientId?.let {
            require(it.isNotEmpty()) { "recipient id cannot be empty" }
            require(it.isNotBlank()) { "recipient id cannot be blank" }
        }
        body?.let {
            require(it.isNotEmpty()) { "message body cannot be empty" }
            require(it.isNotBlank()) { "message body cannot be blank" }
        }
        content?.let {
            require(it !is ContentBody.Article) { "message content cannot be an article" }
        }
        require(validateConversationId()) { "conversation id does not match sender and recipient" }
    }

    private fun validateConversationId(): Boolean {
        val recipientId = recipientId ?: return true
        return conversationId == conversationId(senderId, recipientId)
    }

    companion object {
        const val SCHEMA = 1

        /**
         * Derives a stable [Uuid] for the direct conversation between [peerA] and [peerB].
         * The result is symmetric: `conversationId(a, b) == conversationId(b, a)`.
         */
        fun conversationId(peerA: String, peerB: String): Uuid {
            val (first, second) = if (peerA <= peerB) peerA to peerB else peerB to peerA
            val hash = CryptoProvider.sha256("$first:$second".encodeToByteArray())
            return Uuid.fromByteArray(hash.copyOf(16))
        }
    }
}
