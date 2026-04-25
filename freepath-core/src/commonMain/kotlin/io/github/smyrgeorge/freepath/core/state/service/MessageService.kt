package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.MessageEntry
import io.github.smyrgeorge.freepath.database.MessageEntryRepository
import io.github.smyrgeorge.freepath.database.MessageStatus
import io.github.smyrgeorge.freepath.model.content.Message
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

class MessageService(
    override val db: ISQLite,
    private val identityService: IdentityService,
    private val messageRepository: MessageEntryRepository,
) : Service {
    private val peerId: String get() = identityService.peerId

    context(db: QueryExecutor)
    suspend fun getConversation(
        otherPeerId: String,
        limit: Int = 50,
        offset: Int = 0,
    ): List<MessageEntry> {
        val conversationId = Message.conversationId(peerId, otherPeerId)
        return messageRepository.findAllByConversationId(conversationId, limit, offset).getOrThrow()
    }

    context(db: QueryExecutor)
    suspend fun save(
        entry: MessageEntry,
        status: MessageStatus
    ): MessageEntry {
        val updated = entry.copy(status = status)
        return messageRepository.update(updated).getOrThrow()
    }

    context(db: QueryExecutor)
    suspend fun save(
        message: Message,
        status: MessageStatus
    ): MessageEntry {
        val entry = MessageEntry.from(message = message, status = status)
        return messageRepository.insert(entry).getOrThrow()
    }

    context(db: Transaction)
    suspend fun deleteAll() {
        messageRepository.deleteAll().getOrThrow()
    }
}
