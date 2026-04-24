package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.ContentConverter
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.freepath.model.content.Content
import io.github.smyrgeorge.freepath.model.content.ContentType
import io.github.smyrgeorge.sqlx4k.annotation.Column
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
    @Column(insert = false, update = false)
    val contentId: String,
    @Column(insert = false, update = false)
    val type: ContentType,
    @Column(insert = false, update = false)
    val authorId: String,
    @Column(insert = false, update = false)
    val version: Int,
    @Column(insert = false, update = false)
    val signature: String,
    val trust: ContentTrust = ContentTrust.UNKNOWN,
    @Converter(ContentConverter::class)
    val content: Content,
) : Auditable<Int> {
    companion object {
        fun from(
            content: Content,
            id: Int = 0,
            trust: ContentTrust = ContentTrust.UNKNOWN
        ): ContentEntry =
            ContentEntry(
                id = id,
                contentId = content.id,
                type = content.type,
                authorId = content.authorId,
                version = content.version,
                signature = content.signature,
                trust = trust,
                content = content,
            )
    }
}
