package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.RelayEntry
import io.github.smyrgeorge.freepath.database.RelayEntryRepository
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

class RelayService(
    private val db: ISQLite,
    private val relayRepository: RelayEntryRepository,
) {
    suspend fun findAll(limit: Int): List<RelayEntry> =
        relayRepository.findAllByLimit(db, limit).getOrThrow()

    suspend fun save(entry: RelayEntry): RelayEntry =
        relayRepository.save(db, entry).getOrThrow()

    suspend fun deleteById(id: Int): Long =
        relayRepository.deleteById(db, id).getOrThrow()

    suspend fun deleteAll(tx: Transaction) {
        relayRepository.deleteAll(tx).getOrThrow()
    }
}