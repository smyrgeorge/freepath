package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Clock
import kotlin.time.Instant

@Table("contact_encounter")
data class ContactEncounterEntry(
    @Id
    override val id: Int = 0,
    @Converter(InstantConverter::class)
    override var createdAt: Instant = Clock.System.now(),
    @Converter(InstantConverter::class)
    override var updatedAt: Instant = Clock.System.now(),
    /** Peer node ID we have met — references ContactEntry.peerId. */
    val peerId: String,
    /** When we last encountered (identified) this peer. */
    @Converter(InstantConverter::class)
    val lastSeenAt: Instant = Clock.System.now(),
    /** How many times we have encountered this peer. */
    val count: Int = 1,
) : Auditable<Int>
