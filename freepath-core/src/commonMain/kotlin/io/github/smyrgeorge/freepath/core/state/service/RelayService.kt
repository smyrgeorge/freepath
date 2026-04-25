package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.RelayEntry
import io.github.smyrgeorge.freepath.database.RelayEntryRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

class RelayService(
    override val db: ISQLite,
    private val relayRepository: RelayEntryRepository,
) : Service {
    context(db: QueryExecutor)
    suspend fun findAll(limit: Int): List<RelayEntry> =
        relayRepository.findAllByLimit(limit).getOrThrow()

    context(db: QueryExecutor)
    suspend fun save(entry: RelayEntry): RelayEntry =
        relayRepository.save(entry).getOrThrow()

    context(db: QueryExecutor)
    suspend fun deleteById(id: Int) {
        relayRepository.deleteById(id).getOrThrow()
    }

    context(db: Transaction)
    suspend fun deleteAll() {
        relayRepository.deleteAll().getOrThrow()
    }
}
