package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface RelayEntryRepository : AuditableRepository<RelayEntry> {
    @Query("SELECT * FROM relay ORDER BY id ASC LIMIT :limit")
    context(context: QueryExecutor)
    suspend fun findAllByLimit(limit: Int): Result<List<RelayEntry>>

    @Query("DELETE FROM relay WHERE id = :id")
    context(context: QueryExecutor)
    suspend fun deleteById(id: Int): Result<Long>

    @Query("DELETE FROM relay")
    context(context: QueryExecutor)
    suspend fun deleteAll(): Result<Long>

    /** Removes entries whose TTL has reached zero (ttl is a generated column). */
    @Query("DELETE FROM relay WHERE ttl <= 0")
    context(context: QueryExecutor)
    suspend fun executeDeleteExpiredTtl(): Result<Long>
}
