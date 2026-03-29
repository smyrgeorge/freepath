package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Clock
import kotlin.time.Instant

@Table("contact_routing")
data class ContactRoutingEntry(
    @Id
    override val id: Int = 0,
    @Converter(InstantConverter::class)
    override var createdAt: Instant = Clock.System.now(),
    @Converter(InstantConverter::class)
    override var updatedAt: Instant = Clock.System.now(),
    /** Peer node ID — references ContactEntry.peerId. */
    val peerId: String,
    /** BLE peripheral ID assigned by the OS (stable UUID per app on iOS; randomized MAC on Android — may change on restart). Stored as TEXT. */
    val blePeripheralId: String? = null,
    /** When the BLE route was last confirmed (i.e. last successful exchange). */
    @Converter(InstantConverter::class)
    val bleUpdatedAt: Instant? = null,
) : Auditable<Int>
