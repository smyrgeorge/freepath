package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContactEntryRepository : AuditableRepository<ContactEntry> {
    @Query("SELECT * FROM contact")
    context(context: QueryExecutor)
    suspend fun findAll(): Result<List<ContactEntry>>

    @Query("SELECT * FROM contact WHERE peer_id = :peerId")
    context(context: QueryExecutor)
    suspend fun findOneByPeerId(peerId: String): Result<ContactEntry?>

    @Query("DELETE FROM contact")
    context(context: QueryExecutor)
    suspend fun deleteAll(): Result<Long>
}
