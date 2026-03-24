package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.content.ContentType
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
    val type: ContentType,
    val authorId: String,
    val version: Int,
    @Converter(ContentConverter::class)
    val content: Content,
) : Auditable<Int>
