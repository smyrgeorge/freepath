package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.content.ContentEnvelope
import io.github.smyrgeorge.freepath.content.ContentType
import io.github.smyrgeorge.freepath.content.Visibility
import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.ContentConverter
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Clock
import kotlin.time.Instant

@Table("content")
data class ContentEntry(
    @Id
    override val id: Int = 0,
    @Converter(InstantConverter::class)
    override var createdAt: Instant = Clock.System.now(),
    @Converter(InstantConverter::class)
    override var updatedAt: Instant = Clock.System.now(),
    val contentId: String,
    val rootId: String,
    val prevId: String? = null,
    val type: ContentType,
    val authorId: String,
    val version: Int,
    val isLatest: Boolean = true,
    val contentCreatedAt: Long,
    val expiresAt: Long? = null,
    val commentsEnabled: Boolean,
    val visibility: Visibility,
    val parentId: String? = null,
    val parentRootId: String? = null,
    val hops: Int = 0,
    @Converter(ContentConverter::class)
    val envelope: ContentEnvelope,
) : Auditable<Int>
