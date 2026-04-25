package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface IdentityEntryRepository : AuditableRepository<IdentityEntry> {
    @Query("SELECT * FROM identity")
    context(context: QueryExecutor)
    suspend fun findAll(): Result<List<IdentityEntry>>

    @Query("DELETE FROM identity")
    context(context: QueryExecutor)
    suspend fun deleteAll(): Result<Long>
}
