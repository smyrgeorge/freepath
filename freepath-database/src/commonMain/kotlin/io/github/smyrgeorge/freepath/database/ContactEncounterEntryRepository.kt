package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContactEncounterEntryRepository : AuditableRepository<ContactEncounterEntry> {
    @Query("SELECT * FROM contact_encounter WHERE peer_id = :peerId")
    context(context: QueryExecutor)
    suspend fun findOneByPeerId(peerId: String): Result<ContactEncounterEntry?>

    @Query("DELETE FROM contact_encounter")
    context(context: QueryExecutor)
    suspend fun deleteAll(): Result<Long>
}
