package io.github.smyrgeorge.freepath.content

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.codec.Base58
import io.github.smyrgeorge.freepath.util.codec.ProtobufCodec
import io.github.smyrgeorge.freepath.util.serializer.InstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalSerializationApi::class, ExperimentalUuidApi::class)
object MessageCodec {

    fun sign(message: Message, sigKeyPrivate: ByteArray): ByteArray {
        val bytes = ProtobufCodec.protobuf.encodeToByteArray(toSignable(message))
        return CryptoProvider.ed25519Sign(sigKeyPrivate, bytes)
    }

    fun verify(message: Message, sigKeyPublic: String): Boolean =
        verify(message, Base64.decode(sigKeyPublic))

    fun verify(message: Message, sigKeyPublic: ByteArray): Boolean {
        val signatureBytes = Base64.decode(message.signature)
        val bytes = ProtobufCodec.protobuf.encodeToByteArray(toSignable(message))
        return CryptoProvider.ed25519Verify(sigKeyPublic, bytes, signatureBytes)
    }

    fun seal(
        sigKeyPrivate: ByteArray,
        conversationId: Uuid,
        senderId: String,
        recipientId: String? = null,
        timestamp: Instant = Clock.System.now(),
        body: String? = null,
        content: ContentBody? = null,
    ): Message {
        val placeholder = Message(
            id = deriveId(conversationId, timestamp, body, content),
            schema = Message.SCHEMA,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            signature = "",
            timestamp = timestamp,
            body = body,
            content = content,
        )
        val signature = Base64.encode(sign(placeholder, sigKeyPrivate))
        return placeholder.copy(signature = signature)
    }

    fun encode(message: Message): ByteArray = ProtobufCodec.protobuf.encodeToByteArray(message)
    fun decode(data: ByteArray): Result<Message> = runCatching {
        ProtobufCodec.protobuf.decodeFromByteArray<Message>(data)
    }

    /**
     * Derives a stable, content-addressed ID from the message payload fields.
     * Uses [IdSource] (no id, no schema, no signature) so the ID can be computed
     * before constructing the [Message].
     */
    fun deriveId(
        conversationId: Uuid,
        timestamp: Instant,
        body: String?,
        content: ContentBody?,
    ): String {
        val idSource = IdSource(
            conversationId = conversationId,
            timestamp = timestamp,
            body = body,
            content = content,
        )
        val bytes = ProtobufCodec.protobuf.encodeToByteArray(idSource)
        return Base58.encode(CryptoProvider.sha256(bytes))
    }

    private fun toSignable(message: Message): Signable = Signable(
        id = message.id,
        schema = message.schema,
        conversationId = message.conversationId,
        senderId = message.senderId,
        recipientId = message.recipientId,
        timestamp = message.timestamp,
        body = message.body,
        content = message.content,
    )

    @Serializable
    private data class Signable(
        @ProtoNumber(1) val id: String,
        @ProtoNumber(2) val schema: Int,
        @ProtoNumber(3) val conversationId: Uuid,
        @ProtoNumber(4) val senderId: String,
        @ProtoNumber(5) val recipientId: String? = null,
        @ProtoNumber(6) @Serializable(with = InstantSerializer::class) val timestamp: Instant,
        @ProtoNumber(7) val body: String? = null,
        @ProtoNumber(8) val content: ContentBody? = null,
    )

    @Serializable
    private data class IdSource(
        @ProtoNumber(1) val conversationId: Uuid,
        @ProtoNumber(2) @Serializable(with = InstantSerializer::class) val timestamp: Instant,
        @ProtoNumber(3) val body: String? = null,
        @ProtoNumber(4) val content: ContentBody? = null,
    )
}
