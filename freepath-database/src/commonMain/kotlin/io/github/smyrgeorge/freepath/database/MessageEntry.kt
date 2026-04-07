package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.content.Message
import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.freepath.database.util.MessageConverter
import io.github.smyrgeorge.sqlx4k.annotation.Column
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
@Table("message")
data class MessageEntry(
    @Id
    override val id: Int = 0,
    @Converter(InstantConverter::class)
    override var createdAt: Instant = Clock.System.now(),
    @Converter(InstantConverter::class)
    override var updatedAt: Instant = Clock.System.now(),
    @Column(insert = false, update = false)
    val messageId: String,
    @Column(insert = false, update = false)
    val conversationId: String,
    @Column(insert = false, update = false)
    val senderId: String,
    @Column(insert = false, update = false)
    val recipientId: String?,
    @Column(insert = false, update = false)
    val signature: String,
    @Column(insert = false, update = false)
    @Converter(InstantConverter::class)
    val timestamp: Instant,
    val status: MessageStatus,
    @Converter(MessageConverter::class)
    val message: Message,
) : Auditable<Int> {
    companion object {
        fun from(message: Message, status: MessageStatus, id: Int = 0): MessageEntry =
            MessageEntry(
                id = id,
                messageId = message.id,
                conversationId = message.conversationId.toString(),
                senderId = message.senderId,
                recipientId = message.recipientId,
                signature = message.signature,
                timestamp = message.timestamp,
                status = status,
                message = message,
            )
    }
}
