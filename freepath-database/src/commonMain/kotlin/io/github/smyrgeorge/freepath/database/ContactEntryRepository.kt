package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContactEntryRepository : AuditableRepository<ContactEntry> {
    @Query("SELECT * FROM contact")
    suspend fun findAll(context: QueryExecutor): Result<List<ContactEntry>>

    @Query("SELECT * FROM contact WHERE peer_id = :peerId")
    suspend fun findOneByPeerId(context: QueryExecutor, peerId: String): Result<ContactEntry?>

    @Query("DELETE FROM contact")
    suspend fun deleteAll(context: QueryExecutor): Result<Long>
}
