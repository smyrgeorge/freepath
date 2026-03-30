package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Clock
import kotlin.time.Instant

@Table("content_sync")
data class ContentSyncEntry(
    @Id
    override val id: Int = 0,
    @Converter(InstantConverter::class)
    override var createdAt: Instant = Clock.System.now(),
    @Converter(InstantConverter::class)
    override var updatedAt: Instant = Clock.System.now(),
    /** The remote peer this content was synced to. */
    val peerId: String,
    /** The content ID that was synced. */
    val contentId: String,
    /** The version of the content at sync time. */
    val version: Int,
    /** When the content was synced to the peer. */
    @Converter(InstantConverter::class)
    val syncedAt: Instant = Clock.System.now(),
) : Auditable<Int>
