package io.github.smyrgeorge.freepath.model.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ContentBodyTest {

    // ── Article ───────────────────────────────────────────────────────────────

    @Test
    fun article_acceptsTitleAtMaxLength() {
        ContentBody.Article(title = "t".repeat(ContentBody.Article.MAX_TITLE_LENGTH), body = "b")
    }

    @Test
    fun article_rejectsTitleTooLong() {
        assertFails {
            ContentBody.Article(title = "t".repeat(ContentBody.Article.MAX_TITLE_LENGTH + 1), body = "b")
        }
    }

    @Test
    fun article_acceptsBodyAtMaxLength() {
        ContentBody.Article(title = "t", body = "b".repeat(ContentBody.Article.MAX_BODY_LENGTH))
    }

    @Test
    fun article_rejectsBodyTooLong() {
        assertFails {
            ContentBody.Article(title = "t", body = "b".repeat(ContentBody.Article.MAX_BODY_LENGTH + 1))
        }
    }

    @Test
    fun article_acceptsEmptyStrings() {
        ContentBody.Article(title = "", body = "")
    }

    @Test
    fun article_embedsAuthor() {
        val author = Author(name = "Alice")
        val body = ContentBody.Article(title = "t", body = "b", author = author)
        assertEquals(author, body.author)
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private fun imageOf(
        width: Int = 10,
        height: Int = 10,
        caption: String? = null,
        data: String = "AAAA",
        format: ImageFormat = ImageFormat.PNG,
    ) = ContentBody.Image(data = data, format = format, width = width, height = height, caption = caption)

    @Test
    fun image_rejectsZeroWidth() {
        assertFails { imageOf(width = 0) }
    }

    @Test
    fun image_rejectsNegativeWidth() {
        assertFails { imageOf(width = -1) }
    }

    @Test
    fun image_rejectsZeroHeight() {
        assertFails { imageOf(height = 0) }
    }

    @Test
    fun image_rejectsNegativeHeight() {
        assertFails { imageOf(height = -1) }
    }

    @Test
    fun image_acceptsOneByOne() {
        imageOf(width = 1, height = 1)
    }

    @Test
    fun image_acceptsCaptionAtMaxLength() {
        imageOf(caption = "c".repeat(ContentBody.Image.MAX_CAPTION_LENGTH))
    }

    @Test
    fun image_rejectsCaptionTooLong() {
        assertFails {
            imageOf(caption = "c".repeat(ContentBody.Image.MAX_CAPTION_LENGTH + 1))
        }
    }

    @Test
    fun image_acceptsDataAtMaxLength() {
        imageOf(data = "a".repeat(ContentBody.Image.MAX_DATA_LENGTH))
    }

    @Test
    fun image_rejectsDataTooLong() {
        assertFails {
            imageOf(data = "a".repeat(ContentBody.Image.MAX_DATA_LENGTH + 1))
        }
    }

    @Test
    fun image_acceptsJpegAndPng() {
        assertEquals(ImageFormat.JPEG, imageOf(format = ImageFormat.JPEG).format)
        assertEquals(ImageFormat.PNG, imageOf(format = ImageFormat.PNG).format)
    }

    // ── Contact ───────────────────────────────────────────────────────────────

    @Test
    fun contact_acceptsAllNull() {
        ContentBody.Contact(bio = null, avatar = null, location = null)
    }

    @Test
    fun contact_acceptsBioAtMaxLength() {
        ContentBody.Contact(
            bio = "b".repeat(ContentBody.Contact.MAX_BIO_LENGTH),
            avatar = null,
            location = null,
        )
    }

    @Test
    fun contact_rejectsBioTooLong() {
        assertFails {
            ContentBody.Contact(
                bio = "b".repeat(ContentBody.Contact.MAX_BIO_LENGTH + 1),
                avatar = null,
                location = null,
            )
        }
    }

    @Test
    fun contact_acceptsAvatarAtMaxSize() {
        ContentBody.Contact(
            bio = null,
            avatar = "x".repeat(ContentBody.Contact.MAX_AVATAR_SIZE),
            location = null,
        )
    }

    @Test
    fun contact_rejectsAvatarTooLarge() {
        assertFails {
            ContentBody.Contact(
                bio = null,
                avatar = "x".repeat(ContentBody.Contact.MAX_AVATAR_SIZE + 1),
                location = null,
            )
        }
    }

    @Test
    fun contact_acceptsLocationAtMaxLength() {
        ContentBody.Contact(
            bio = null,
            avatar = null,
            location = "l".repeat(ContentBody.Contact.MAX_LOCATION_LENGTH),
        )
    }

    @Test
    fun contact_rejectsLocationTooLong() {
        assertFails {
            ContentBody.Contact(
                bio = null,
                avatar = null,
                location = "l".repeat(ContentBody.Contact.MAX_LOCATION_LENGTH + 1),
            )
        }
    }
}
