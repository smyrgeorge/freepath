package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.ContactEncounterEntry
import io.github.smyrgeorge.freepath.database.ContactEncounterEntryRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlin.time.Clock

class ContactEncounterService(
    override val db: ISQLite,
    private val repository: ContactEncounterEntryRepository,
) : Service {
    context(db: QueryExecutor)
    suspend fun recordEncounter(peerId: String): ContactEncounterEntry {
        val now = Clock.System.now()
        val existing = repository.findOneByPeerId(peerId).getOrThrow()
        val entry = existing
            ?.copy(lastSeenAt = now, count = existing.count + 1)
            ?: ContactEncounterEntry(peerId = peerId)
        return repository.save(entry).getOrThrow()
    }

    context(db: QueryExecutor)
    suspend fun getByPeerId(peerId: String): ContactEncounterEntry? =
        repository.findOneByPeerId(peerId).getOrThrow()

    context(db: Transaction)
    suspend fun deleteAll() {
        repository.deleteAll().getOrThrow()
    }
}
