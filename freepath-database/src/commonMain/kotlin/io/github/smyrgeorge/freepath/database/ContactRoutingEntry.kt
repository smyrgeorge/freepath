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
    /** When the BLE route was last confirmed (i.e. last successful exchange or token match). */
    @Converter(InstantConverter::class)
    val bleUpdatedAt: Instant? = null,
    /** Shared secret derived during BLE contact exchange, used for rotating identity token computation. Base64-encoded 32 bytes. */
    val bleIdentitySecret: String? = null,
) : Auditable<Int>
