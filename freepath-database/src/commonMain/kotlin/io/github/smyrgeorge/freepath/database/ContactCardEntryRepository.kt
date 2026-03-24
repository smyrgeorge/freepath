package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContactCardEntryRepository : AuditableRepository<ContactCardEntry> {
    @Query("SELECT * FROM contact")
    suspend fun findAll(context: QueryExecutor): Result<List<ContactCardEntry>>

    @Query("SELECT * FROM contact WHERE peer_id = :peerId")
    suspend fun findOneByPeerId(context: QueryExecutor, peerId: String): Result<ContactCardEntry?>

    @Query("DELETE FROM contact")
    suspend fun deleteAll(context: QueryExecutor): Result<Long>
}