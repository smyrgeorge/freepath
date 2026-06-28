package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface RelayOfferedEntryRepository : AuditableRepository<RelayOfferedEntry> {
    @Query("SELECT * FROM relay_offered WHERE peer_id = :peerId")
    context(context: QueryExecutor)
    suspend fun findAllByPeerId(peerId: String): Result<List<RelayOfferedEntry>>

    /** Reap rows whose replica is gone (delivered / swept) — orphan GC, since FKs are not enforced. */
    @Query("DELETE FROM relay_offered WHERE relay_entry_id NOT IN (SELECT id FROM relay)")
    context(context: QueryExecutor)
    suspend fun executeDeleteOrphaned(): Result<Long>

    @Query("DELETE FROM relay_offered")
    context(context: QueryExecutor)
    suspend fun deleteAll(): Result<Long>
}
