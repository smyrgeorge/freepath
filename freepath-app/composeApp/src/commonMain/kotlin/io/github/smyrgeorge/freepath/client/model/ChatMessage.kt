package io.github.smyrgeorge.freepath.client.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Clock

@OptIn(ExperimentalSerializationApi::class)
data class ChatMessage(
    val senderId: String,
    val receiverId: String,
    val text: String,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
) {
    fun encodeToByteArray(): ByteArray =
        ProtoBuf.encodeToByteArray(Dto.serializer(), Dto(text, timestamp))

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
                timestamp = wire.timestamp
            )
        }
    }
}
