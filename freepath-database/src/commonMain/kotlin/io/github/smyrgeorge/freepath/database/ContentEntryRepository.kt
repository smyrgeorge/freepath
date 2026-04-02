package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContentEntryRepository : AuditableRepository<ContentEntry> {
    @Query("SELECT * FROM content WHERE content_id = :contentId")
    suspend fun findOneByContentId(context: QueryExecutor, contentId: String): Result<ContentEntry?>

    @Query("SELECT * FROM content WHERE author_id = :authorId AND type = 'CONTACT' ORDER BY id DESC LIMIT 1")
    suspend fun findOneByAuthorIdAndTypeContact(context: QueryExecutor, authorId: String): Result<ContentEntry?>

    @Query("SELECT * FROM content WHERE type NOT IN ('CONTACT') ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun findAllByLimitAndOffset(context: QueryExecutor, limit: Int, offset: Int): Result<List<ContentEntry>>

    @Query("DELETE FROM content")
    suspend fun deleteAll(context: QueryExecutor): Result<Long>
}
