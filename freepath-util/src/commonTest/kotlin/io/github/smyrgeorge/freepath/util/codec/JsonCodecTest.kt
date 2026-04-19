package io.github.smyrgeorge.freepath.util.codec

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonCodecTest {

    @Serializable
    private data class Simple(val a: Int = 7, val b: String = "hi")

    @Serializable
    private data class WithNullable(val a: Int, val b: String? = null)

    @Serializable
    private data class WithBytes(@Contextual val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is WithBytes && payload.contentEquals(other.payload)

        override fun hashCode(): Int = payload.contentHashCode()
    }

    @Serializable
    private sealed interface Animal {
        @Serializable
        @SerialName("dog")
        data class Dog(val name: String) : Animal

        @Serializable
        @SerialName("cat")
        data class Cat(val whiskers: Int) : Animal
    }

    @Test
    fun `encodeDefaults writes default-valued fields`() {
        val json = JsonCodec.json.encodeToString(Simple())
        assertEquals("""{"a":7,"b":"hi"}""", json)
    }

    @Test
    fun `ignoreUnknownKeys drops unknown fields on decode`() {
        val decoded = JsonCodec.json.decodeFromString<Simple>("""{"a":1,"b":"x","extra":42}""")
        assertEquals(Simple(a = 1, b = "x"), decoded)
    }

    @Test
    fun `explicitNulls false omits nulls on encode`() {
        val encoded = JsonCodec.json.encodeToString(WithNullable(a = 1, b = null))
        assertEquals("""{"a":1}""", encoded)
    }

    @Test
    fun `explicitNulls false allows missing field to decode as null`() {
        val decoded = JsonCodec.json.decodeFromString<WithNullable>("""{"a":1}""")
        assertEquals(1, decoded.a)
        assertNull(decoded.b)
    }

    @Test
    fun `coerceInputValues false rejects null for non-nullable field`() {
        assertFailsWith<SerializationException> {
            JsonCodec.json.decodeFromString<Simple>("""{"a":null,"b":"x"}""")
        }
    }

    @Test
    fun `isLenient false rejects unquoted strings`() {
        assertFailsWith<SerializationException> {
            JsonCodec.json.decodeFromString<Simple>("""{"a":1,"b":raw}""")
        }
    }

    @Test
    fun `ByteArray uses Base64 contextual serializer`() {
        val src = WithBytes(byteArrayOf(0, 1, 2, 3, -1, 127, -128))
        val encoded = JsonCodec.json.encodeToString(src)
        // base64 of 0x00 01 02 03 FF 7F 80 = "AAECA/9/gA=="
        assertEquals("""{"payload":"AAECA/9/gA=="}""", encoded)
        val decoded = JsonCodec.json.decodeFromString<WithBytes>(encoded)
        assertContentEquals(src.payload, decoded.payload)
    }

    @Test
    fun `polymorphic uses type discriminator`() {
        val module = JsonCodec.json.serializersModule + SerializersModule {
            polymorphic(Animal::class) {
                subclass(Animal.Dog::class)
                subclass(Animal.Cat::class)
            }
        }
        val codec = kotlinx.serialization.json.Json(from = JsonCodec.json) {
            serializersModule = module
        }
        val dog: Animal = Animal.Dog("rex")
        val encoded = codec.encodeToString(dog)
        assertEquals("""{"@type":"dog","name":"rex"}""", encoded)
        val decoded = codec.decodeFromString<Animal>(encoded)
        assertEquals(dog, decoded)
    }
}
