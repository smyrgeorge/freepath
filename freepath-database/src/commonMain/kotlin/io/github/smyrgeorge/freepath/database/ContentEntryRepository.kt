package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContentEntryRepository : AuditableRepository<ContentEntry> {
    @Query("SELECT * FROM content WHERE content_id = :contentId")
    context(context: QueryExecutor)
    suspend fun findOneByContentId(contentId: String): Result<ContentEntry?>

    @Query("SELECT * FROM content WHERE author_id = :authorId AND type = 'CONTACT' ORDER BY id DESC LIMIT 1")
    context(context: QueryExecutor)
    suspend fun findOneByAuthorIdAndTypeContact(authorId: String): Result<ContentEntry?>

    @Query("SELECT * FROM content WHERE type NOT IN ('CONTACT') ORDER BY id DESC LIMIT :limit OFFSET :offset")
    context(context: QueryExecutor)
    suspend fun findAllByLimitAndOffset(limit: Int, offset: Int): Result<List<ContentEntry>>

    @Query("SELECT * FROM content WHERE author_id = :authorId AND type NOT IN ('CONTACT') ORDER BY id DESC LIMIT :limit OFFSET :offset")
    context(context: QueryExecutor)
    suspend fun findAllByAuthorIdAndLimitAndOffset(
        authorId: String,
        limit: Int,
        offset: Int,
    ): Result<List<ContentEntry>>

    @Query("DELETE FROM content")
    context(context: QueryExecutor)
    suspend fun deleteAll(): Result<Long>
}
