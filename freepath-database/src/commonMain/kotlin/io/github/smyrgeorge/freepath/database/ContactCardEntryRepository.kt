package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContactCardEntryRepository : AuditableRepository<ContactCardEntry> {
    @Query("SELECT * FROM contact WHERE node_id = :nodeId")
    suspend fun findOneByNodeId(context: QueryExecutor, nodeId: String): Result<ContactCardEntry?>
}