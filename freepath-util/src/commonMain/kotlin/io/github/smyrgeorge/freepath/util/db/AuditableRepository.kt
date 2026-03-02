package io.github.smyrgeorge.freepath.util.db

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlin.time.Clock

interface AuditableRepository<T : Auditable<*>> : CrudRepository<T> {
    override suspend fun preInsertHook(context: QueryExecutor, entity: T): T {
        entity.createdAt = Clock.System.now()
        entity.updatedAt = entity.createdAt
        return entity
    }

    override suspend fun preUpdateHook(context: QueryExecutor, entity: T): T {
        entity.updatedAt = Clock.System.now()
        return entity
    }
}
