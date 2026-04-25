package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContentSyncEntryRepository : AuditableRepository<ContentSyncEntry> {
    @Query("SELECT * FROM content_sync WHERE peer_id = :peerId AND content_id = :contentId LIMIT 1")
    context(context: QueryExecutor)
    suspend fun findOneByPeerIdAndContentId(
        peerId: String,
        contentId: String
    ): Result<ContentSyncEntry?>

    @Query("DELETE FROM content_sync")
    context(context: QueryExecutor)
    suspend fun deleteAll(): Result<Long>
}
