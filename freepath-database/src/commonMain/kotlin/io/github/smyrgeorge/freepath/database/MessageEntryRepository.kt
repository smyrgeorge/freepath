package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import kotlin.uuid.Uuid

@Repository
interface MessageEntryRepository : AuditableRepository<MessageEntry> {
    @Query("SELECT * FROM message WHERE conversation_id = :conversationId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    context(context: QueryExecutor)
    suspend fun findAllByConversationId(
        conversationId: Uuid,
        limit: Int,
        offset: Int
    ): Result<List<MessageEntry>>

    @Query("DELETE FROM message")
    context(context: QueryExecutor)
    suspend fun deleteAll(): Result<Long>
}
