package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContactRoutingEntryRepository : AuditableRepository<ContactRoutingEntry> {
    @Query("SELECT * FROM contact_routing WHERE peer_id = :peerId")
    suspend fun findOneByPeerId(context: QueryExecutor, peerId: String): Result<ContactRoutingEntry?>

    @Query("SELECT * FROM contact_routing WHERE ble_peripheral_id = :blePeripheralId")
    suspend fun findOneByBlePeripheralId(context: QueryExecutor, blePeripheralId: String): Result<ContactRoutingEntry?>

    @Query("DELETE FROM contact_routing")
    suspend fun deleteAll(context: QueryExecutor): Result<Long>
}
