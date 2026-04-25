package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.MessageEntry
import io.github.smyrgeorge.freepath.database.MessageEntryRepository
import io.github.smyrgeorge.freepath.database.MessageStatus
import io.github.smyrgeorge.freepath.model.content.Message
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

class MessageService(
    private val db: ISQLite,
    private val identityService: IdentityService,
    private val messageRepository: MessageEntryRepository,
) {
    private val peerId: String get() = identityService.peerId

    suspend fun save(message: Message, status: MessageStatus): MessageEntry = save(db, message, status)
    suspend fun save(db: QueryExecutor, message: Message, status: MessageStatus): MessageEntry {
        val entry = MessageEntry.from(message = message, status = status)
        return messageRepository.insert(db, entry).getOrThrow()
    }

    suspend fun getChat(otherPeerId: String, limit: Int = 50, offset: Int = 0): List<MessageEntry> =
        getChat(db, otherPeerId, limit, offset)

    suspend fun getChat(
        db: QueryExecutor,
        otherPeerId: String,
        limit: Int = 50,
        offset: Int = 0,
    ): List<MessageEntry> {
        val conversationId = Message.conversationId(peerId, otherPeerId)
        return messageRepository.findAllByConversationId(db, conversationId, limit, offset).getOrThrow()
    }

    suspend fun updateStatus(entry: MessageEntry, status: MessageStatus): MessageEntry = updateStatus(db, entry, status)
    suspend fun updateStatus(db: QueryExecutor, entry: MessageEntry, status: MessageStatus): MessageEntry {
        val updated = entry.copy(status = status)
        return messageRepository.update(db, updated).getOrThrow()
    }

    suspend fun deleteAll(tx: Transaction) {
        messageRepository.deleteAll(tx).getOrThrow()
    }
}
