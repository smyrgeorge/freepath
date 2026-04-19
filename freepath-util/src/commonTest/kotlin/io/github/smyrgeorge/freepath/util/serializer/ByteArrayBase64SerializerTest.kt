package io.github.smyrgeorge.freepath.util.serializer

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteArrayBase64SerializerTest {

    private val json = Json

    @Test
    fun descriptor_isPrimitiveString() {
        val d = ByteArrayBase64Serializer.descriptor
        assertEquals("ByteArrayBase64", d.serialName)
        assertEquals(PrimitiveKind.STRING, d.kind)
    }

    @Test
    fun encode_emptyArray_isEmptyString() {
        val encoded = json.encodeToString(ByteArrayBase64Serializer, ByteArray(0))
        assertEquals("\"\"", encoded)
    }

    @Test
    fun decode_emptyString_isEmptyArray() {
        val decoded = json.decodeFromString(ByteArrayBase64Serializer, "\"\"")
        assertEquals(0, decoded.size)
    }

    @Test
    fun encode_rfc4648Vectors() {
        // from RFC 4648 section 10
        val cases = listOf(
            "f" to "Zg==",
            "fo" to "Zm8=",
            "foo" to "Zm9v",
            "foob" to "Zm9vYg==",
            "fooba" to "Zm9vYmE=",
            "foobar" to "Zm9vYmFy",
        )
        for ((text, expected) in cases) {
            val encoded = json.encodeToString(ByteArrayBase64Serializer, text.encodeToByteArray())
            assertEquals("\"$expected\"", encoded, "encode(\"$text\")")
        }
    }

    @Test
    fun decode_rfc4648Vectors() {
        val cases = listOf(
            "Zg==" to "f",
            "Zm8=" to "fo",
            "Zm9v" to "foo",
            "Zm9vYg==" to "foob",
            "Zm9vYmE=" to "fooba",
            "Zm9vYmFy" to "foobar",
        )
        for ((b64, text) in cases) {
            val decoded = json.decodeFromString(ByteArrayBase64Serializer, "\"$b64\"")
            assertEquals(text, decoded.decodeToString(), "decode(\"$b64\")")
        }
    }

    @Test
    fun roundtrip_preservesAllByteValues() {
        val src = ByteArray(256) { it.toByte() }
        val encoded = json.encodeToString(ByteArrayBase64Serializer, src)
        val decoded = json.decodeFromString(ByteArrayBase64Serializer, encoded)
        assertContentEquals(src, decoded)
    }

    @Test
    fun encode_producesUrlUnsafeStandardAlphabet() {
        // 0xFB 0xFF 0xBF -> "+/+/" in standard Base64
        val src = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        val encoded = json.encodeToString(ByteArrayBase64Serializer, src)
        assertEquals("\"+/+/\"", encoded)
    }

    @Test
    fun decode_invalidBase64_throws() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(ByteArrayBase64Serializer, "\"not*valid!\"")
        }
    }

    @Test
    fun decode_nonStringInput_throws() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(ByteArrayBase64Serializer, "123")
        }
    }

    @Test
    fun differsFromDefaultByteArraySerializer() {
        // kotlinx default encodes ByteArray as [1,2,3] JSON array
        val src = byteArrayOf(1, 2, 3)
        val asArray = json.encodeToString(ByteArraySerializer(), src)
        val asBase64 = json.encodeToString(ByteArrayBase64Serializer, src)
        assertEquals("[1,2,3]", asArray)
        assertEquals("\"AQID\"", asBase64)
    }
}
