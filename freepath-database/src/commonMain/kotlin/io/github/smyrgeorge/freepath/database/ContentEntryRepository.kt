package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContentEntryRepository : AuditableRepository<ContentEntry> {

    /** Look up a specific version by its own content ID. */
    @Query("SELECT * FROM content WHERE content_id = :contentId LIMIT 1")
    suspend fun findOneByContentId(context: QueryExecutor, contentId: String): Result<ContentEntry?>

    /**
     * Main feed: top-level content (excludes COMMENT, REACTION, and CONTACT), most recent first.
     * Visibility filtering (e.g. hiding PRIVATE items from other authors) is the caller's responsibility.
     */
    @Query("SELECT * FROM content WHERE type NOT IN ('COMMENT','REACTION','CONTACT') ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun findAllByTopLevel(context: QueryExecutor, limit: Int, offset: Int): Result<List<ContentEntry>>

    /** Hard-delete all content entries. */
    @Query("DELETE FROM content")
    suspend fun deleteAll(context: QueryExecutor): Result<Long>
}
