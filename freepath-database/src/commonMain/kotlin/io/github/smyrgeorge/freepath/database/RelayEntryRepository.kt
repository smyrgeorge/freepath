package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface RelayEntryRepository : AuditableRepository<RelayEntry> {
    /** Fetches up to [limit] entries ordered by insertion time. */
    @Query("SELECT * FROM relay ORDER BY id ASC LIMIT :limit")
    suspend fun findAllByLimit(context: QueryExecutor, limit: Int): Result<List<RelayEntry>>

    @Query("DELETE FROM relay WHERE id = :id")
    suspend fun deleteById(context: QueryExecutor, id: Int): Result<Long>

    @Query("DELETE FROM relay")
    suspend fun deleteAll(context: QueryExecutor): Result<Long>

    /** Removes entries whose TTL has reached zero (ttl is a generated column). */
    @Query("DELETE FROM relay WHERE ttl <= 0")
    suspend fun executeDeleteExpiredTtl(context: QueryExecutor): Result<Long>
}
