package io.github.smyrgeorge.freepath.libnet.client.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
data class ChatMessage(
    val senderId: String,
    val receiverId: String,
    val text: String,
    val timestamp: Instant = Clock.System.now(),
) {
    fun encodeToByteArray(): ByteArray =
        ProtoBuf.encodeToByteArray(Dto.serializer(), Dto(text, timestamp.toEpochMilliseconds()))

    @Serializable
    private data class Dto(
        @ProtoNumber(1) val text: String,
        @ProtoNumber(2) val timestamp: Long,
    )

    companion object {
        fun decodeFromByteArray(bytes: ByteArray, senderId: String, receiverId: String): ChatMessage {
            val wire = ProtoBuf.decodeFromByteArray(Dto.serializer(), bytes)
            return ChatMessage(
                senderId = senderId,
                receiverId = receiverId,
                text = wire.text,
                timestamp = Instant.fromEpochMilliseconds(wire.timestamp),
            )
        }
    }
}
