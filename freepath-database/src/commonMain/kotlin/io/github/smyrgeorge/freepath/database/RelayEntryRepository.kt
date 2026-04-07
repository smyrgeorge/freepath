package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface RelayEntryRepository : AuditableRepository<RelayEntry> {
    @Query("SELECT * FROM relay WHERE expire_at > (strftime('%s', 'now') * 1000) ORDER BY id ASC LIMIT :limit")
    suspend fun findAllByLimit(context: QueryExecutor, limit: Int): Result<List<RelayEntry>>

    @Query("DELETE FROM relay WHERE id = :id")
    suspend fun deleteById(context: QueryExecutor, id: Int): Result<Long>

    @Query("DELETE FROM relay")
    suspend fun deleteAll(context: QueryExecutor): Result<Long>

    @Query("DELETE FROM relay WHERE expire_at <= (strftime('%s', 'now') * 1000)")
    suspend fun executeDeleteExpired(context: QueryExecutor): Result<Long>
}
