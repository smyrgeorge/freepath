package io.github.smyrgeorge.freepath.model.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class AuthorTest {

    @Test
    fun defaults_areAllNull() {
        val a = Author()
        assertNull(a.name)
        assertNull(a.bio)
        assertNull(a.avatar)
        assertNull(a.location)
    }

    @Test
    fun acceptsAllNull() {
        Author(name = null, bio = null, avatar = null, location = null)
    }

    @Test
    fun acceptsShortFields() {
        val a = Author(name = "Alice", bio = "hi", avatar = "A", location = "Athens")
        assertEquals("Alice", a.name)
        assertEquals("hi", a.bio)
    }

    // Note: `name` is bounded by MAX_LOCATION_LENGTH (128) per current impl
    @Test
    fun acceptsNameAtMaxLocationLength() {
        Author(name = "a".repeat(ContentBody.Contact.MAX_LOCATION_LENGTH))
    }

    @Test
    fun rejectsNameTooLong() {
        assertFails {
            Author(name = "a".repeat(ContentBody.Contact.MAX_LOCATION_LENGTH + 1))
        }
    }

    @Test
    fun acceptsBioAtMaxLength() {
        Author(bio = "b".repeat(ContentBody.Contact.MAX_BIO_LENGTH))
    }

    @Test
    fun rejectsBioTooLong() {
        assertFails {
            Author(bio = "b".repeat(ContentBody.Contact.MAX_BIO_LENGTH + 1))
        }
    }

    @Test
    fun acceptsAvatarAtMaxSize() {
        Author(avatar = "x".repeat(ContentBody.Contact.MAX_AVATAR_SIZE))
    }

    @Test
    fun rejectsAvatarTooLarge() {
        assertFails {
            Author(avatar = "x".repeat(ContentBody.Contact.MAX_AVATAR_SIZE + 1))
        }
    }

    @Test
    fun acceptsLocationAtMaxLength() {
        Author(location = "l".repeat(ContentBody.Contact.MAX_LOCATION_LENGTH))
    }

    @Test
    fun rejectsLocationTooLong() {
        assertFails {
            Author(location = "l".repeat(ContentBody.Contact.MAX_LOCATION_LENGTH + 1))
        }
    }

    @Test
    fun dataClassEquality_usesAllFields() {
        val a = Author(name = "A", bio = "b", avatar = "c", location = "d")
        val b = Author(name = "A", bio = "b", avatar = "c", location = "d")
        val c = Author(name = "A", bio = "b", avatar = "c", location = "other")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        kotlin.test.assertNotEquals(a, c)
    }
}
