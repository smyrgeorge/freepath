package io.github.smyrgeorge.freepath.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ContentTest {

    private fun baseArticle() = Content(
        id = "id",
        type = ContentType.ARTICLE,
        authorId = "author",
        signature = "sig",
        body = ContentBody.Article("t", "b"),
        createdAt = Instant.fromEpochMilliseconds(1000L),
    )

    @Test
    fun defaults_schemaAndVersion() {
        val c = baseArticle()
        assertEquals(Content.SCHEMA, c.schema)
        assertEquals(1, c.version)
        assertNull(c.expiresAt)
    }

    @Test
    fun rejectsWrongSchema() {
        assertFails {
            Content(
                id = "id",
                schema = 999,
                type = ContentType.ARTICLE,
                authorId = "author",
                signature = "sig",
                body = ContentBody.Article("t", "b"),
                createdAt = Instant.fromEpochMilliseconds(1L),
            )
        }
    }

    @Test
    fun rejectsVersionZero() {
        assertFails {
            baseArticle().copy(version = 0)
        }
    }

    @Test
    fun rejectsNegativeVersion() {
        assertFails {
            baseArticle().copy(version = -1)
        }
    }

    @Test
    fun acceptsImageTypeWithImageBody() {
        val c = Content(
            id = "id",
            type = ContentType.IMAGE,
            authorId = "author",
            signature = "sig",
            body = ContentBody.Image(
                data = "AAAA",
                format = ImageFormat.PNG,
                width = 1,
                height = 1,
                caption = null,
            ),
            createdAt = Instant.fromEpochMilliseconds(1L),
        )
        assertEquals(ContentType.IMAGE, c.type)
    }

    @Test
    fun acceptsContactTypeWithContactBody() {
        val c = Content(
            id = "id",
            type = ContentType.CONTACT,
            authorId = "author",
            signature = "sig",
            body = ContentBody.Contact(bio = null, avatar = null, location = null),
            createdAt = Instant.fromEpochMilliseconds(1L),
        )
        assertEquals(ContentType.CONTACT, c.type)
    }

    @Test
    fun rejectsImageTypeWithArticleBody() {
        assertFails {
            Content(
                id = "id",
                type = ContentType.IMAGE,
                authorId = "author",
                signature = "sig",
                body = ContentBody.Article("t", "b"),
                createdAt = Instant.fromEpochMilliseconds(1L),
            )
        }
    }

    @Test
    fun rejectsContactTypeWithImageBody() {
        assertFails {
            Content(
                id = "id",
                type = ContentType.CONTACT,
                authorId = "author",
                signature = "sig",
                body = ContentBody.Image(
                    data = "AAAA",
                    format = ImageFormat.JPEG,
                    width = 1,
                    height = 1,
                    caption = null,
                ),
                createdAt = Instant.fromEpochMilliseconds(1L),
            )
        }
    }

    @Test
    fun rejectsArticleTypeWithContactBody() {
        assertFails {
            Content(
                id = "id",
                type = ContentType.ARTICLE,
                authorId = "author",
                signature = "sig",
                body = ContentBody.Contact(bio = null, avatar = null, location = null),
                createdAt = Instant.fromEpochMilliseconds(1L),
            )
        }
    }

    @Test
    fun copy_revalidatesInvariants() {
        val c = baseArticle()
        assertFails {
            c.copy(version = -5)
        }
    }

    @Test
    fun dataClassEquality_differsOnVersion() {
        val v1 = baseArticle()
        val v2 = v1.copy(version = 2)
        assertNotEquals(v1, v2)
    }

    @Test
    fun expiresAt_isPreservedWhenSet() {
        val expiry = Instant.fromEpochMilliseconds(50000L)
        val c = baseArticle().copy(expiresAt = expiry)
        assertEquals(expiry, c.expiresAt)
    }
}
