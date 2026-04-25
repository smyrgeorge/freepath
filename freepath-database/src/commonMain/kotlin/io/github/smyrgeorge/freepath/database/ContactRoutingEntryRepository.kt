package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContactRoutingEntryRepository : AuditableRepository<ContactRoutingEntry> {
    @Query("SELECT * FROM contact_routing WHERE peer_id = :peerId")
    context(context: QueryExecutor)
    suspend fun findOneByPeerId(peerId: String): Result<ContactRoutingEntry?>

    @Query("SELECT * FROM contact_routing WHERE ble_identity_secret IS NOT NULL AND 1=:dummy")
    context(context: QueryExecutor)
    suspend fun findAllByIdentitySecretNotNull(dummy: Int = 1): Result<List<ContactRoutingEntry>>

    @Query("DELETE FROM contact_routing")
    context(context: QueryExecutor)
    suspend fun deleteAll(): Result<Long>
}
