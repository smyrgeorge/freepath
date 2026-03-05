package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.AuditableRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface IdentityEntryRepository : AuditableRepository<IdentityEntry> {
    @Query("SELECT * FROM identity")
    suspend fun findAll(context: QueryExecutor): Result<List<IdentityEntry>>
}
