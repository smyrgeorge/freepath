package io.github.smyrgeorge.freepath.util.codec

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProtobufCodecTest {

    @Serializable
    private data class Primitives(
        val i: Int,
        val l: Long,
        val s: String,
        val b: Boolean,
        val f: Float,
        val d: Double,
    )

    @Serializable
    private data class Nested(val inner: Primitives, val tag: String)

    @Serializable
    private data class Holder(
        val items: List<String>,
        val opt: String? = null,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Holder &&
                items == other.items &&
                opt == other.opt &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = (items.hashCode() * 31 + opt.hashCode()) * 31 + bytes.contentHashCode()
    }

    @Test
    fun `roundtrip primitives`() {
        val src = Primitives(i = -7, l = Long.MAX_VALUE, s = "hi", b = true, f = 1.5f, d = -3.14)
        val bytes = ProtobufCodec.protobuf.encodeToByteArray(src)
        val back = ProtobufCodec.protobuf.decodeFromByteArray<Primitives>(bytes)
        assertEquals(src, back)
    }

    @Test
    fun `roundtrip nested data class`() {
        val src = Nested(
            inner = Primitives(1, 2L, "x", false, 0.0f, 0.0),
            tag = "root",
        )
        val bytes = ProtobufCodec.protobuf.encodeToByteArray(src)
        val back = ProtobufCodec.protobuf.decodeFromByteArray<Nested>(bytes)
        assertEquals(src, back)
    }

    @Test
    fun `roundtrip list and nullable and ByteArray`() {
        val src = Holder(
            items = listOf("a", "b", "c"),
            opt = null,
            bytes = byteArrayOf(0, 1, -1, 127, -128),
        )
        val bytes = ProtobufCodec.protobuf.encodeToByteArray(src)
        val back = ProtobufCodec.protobuf.decodeFromByteArray<Holder>(bytes)
        assertEquals(src, back)
    }

    @Test
    fun `encoding is deterministic for same input`() {
        val src = Primitives(42, 42L, "fp", true, 2.5f, 2.5)
        val a = ProtobufCodec.protobuf.encodeToByteArray(src)
        val b = ProtobufCodec.protobuf.encodeToByteArray(src)
        assertContentEquals(a, b)
    }

    @Test
    fun `protobuf is more compact than json for typical payload`() {
        val src = Primitives(1, 2L, "hello", true, 1.0f, 1.0)
        val proto = ProtobufCodec.protobuf.encodeToByteArray(src)
        val json = JsonCodec.json.encodeToString(
            kotlinx.serialization.serializer<Primitives>(),
            src,
        ).encodeToByteArray()
        assertTrue(proto.size < json.size, "proto=${proto.size} json=${json.size}")
    }

    @Test
    fun `distinct values produce distinct encodings`() {
        val a = ProtobufCodec.protobuf.encodeToByteArray(Primitives(1, 1L, "a", true, 0f, 0.0))
        val b = ProtobufCodec.protobuf.encodeToByteArray(Primitives(2, 1L, "a", true, 0f, 0.0))
        assertNotEquals(a.toList(), b.toList())
    }
}
