package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.RelayEntryRepository
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

class RelayService(
    private val db: ISQLite,
    private val relayRepository: RelayEntryRepository,
) {
    suspend fun deleteAll(tx: Transaction) {
        relayRepository.deleteAll(tx).getOrThrow()
    }
}