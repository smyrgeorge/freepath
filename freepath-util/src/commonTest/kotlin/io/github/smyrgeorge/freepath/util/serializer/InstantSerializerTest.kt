package io.github.smyrgeorge.freepath.util.serializer

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class InstantSerializerTest {

    private val json = Json

    @Test
    fun descriptor_isPrimitiveLong() {
        val d = InstantSerializer.descriptor
        assertEquals("InstantEpochMillis", d.serialName)
        assertEquals(PrimitiveKind.LONG, d.kind)
    }

    @Test
    fun encode_epochIsZero() {
        val encoded = json.encodeToString(InstantSerializer, Instant.fromEpochMilliseconds(0))
        assertEquals("0", encoded)
    }

    @Test
    fun encode_writesEpochMillis() {
        val encoded = json.encodeToString(
            InstantSerializer,
            Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )
        assertEquals("1700000000000", encoded)
    }

    @Test
    fun decode_readsEpochMillis() {
        val decoded = json.decodeFromString(InstantSerializer, "1700000000000")
        assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_000L), decoded)
    }

    @Test
    fun roundtrip_positive() {
        val src = Instant.fromEpochMilliseconds(1_234_567_890_123L)
        val encoded = json.encodeToString(InstantSerializer, src)
        val back = json.decodeFromString(InstantSerializer, encoded)
        assertEquals(src, back)
    }

    @Test
    fun roundtrip_negative_preEpoch() {
        val src = Instant.fromEpochMilliseconds(-86_400_000L)
        val encoded = json.encodeToString(InstantSerializer, src)
        assertEquals("-86400000", encoded)
        val back = json.decodeFromString(InstantSerializer, encoded)
        assertEquals(src, back)
    }

    @Test
    fun roundtrip_largeValue() {
        val src = Instant.fromEpochMilliseconds(Long.MAX_VALUE)
        val encoded = json.encodeToString(InstantSerializer, src)
        val back = json.decodeFromString(InstantSerializer, encoded)
        assertEquals(src, back)
    }

    @Test
    fun serializer_dropsSubMillisecondPrecision() {
        val src = Instant.fromEpochSeconds(0, nanosecondAdjustment = 1_500_000L) // 1.5 ms
        val encoded = json.encodeToString(InstantSerializer, src)
        // 1.5 ms floors to 1 ms
        assertEquals("1", encoded)
        val back = json.decodeFromString(InstantSerializer, encoded)
        assertEquals(Instant.fromEpochMilliseconds(1L), back)
    }

    @Test
    fun decode_nonNumericInput_throws() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(InstantSerializer, "\"not-a-long\"")
        }
    }
}
