package io.github.smyrgeorge.freepath.libble.pool

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BleFrameTest {

    @Test
    fun `encode ping produces 5-byte header with no payload`() {
        val encoded = BleFrame.encode(BleFrameType.PING)
        assertEquals(5, encoded.size)
        // length = 0 (4 bytes BE)
        assertEquals(0, encoded[0].toInt())
        assertEquals(0, encoded[1].toInt())
        assertEquals(0, encoded[2].toInt())
        assertEquals(0, encoded[3].toInt())
        // type byte
        assertEquals(0x04, encoded[4].toInt())
    }

    @Test
    fun `encode and decode round-trip for exchange frame`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = BleFrame.encode(BleFrameType.EXCHANGE, payload)
        assertEquals(10, encoded.size) // 5 header + 5 payload
        val frame = BleFrame.decode(encoded)
        assertEquals(BleFrameType.EXCHANGE, frame.type)
        assertContentEquals(payload, frame.payload)
    }

    @Test
    fun `encode and decode round-trip for response frame with large payload`() {
        val payload = ByteArray(256) { it.toByte() }
        val encoded = BleFrame.encode(BleFrameType.RESPONSE, payload)
        val frame = BleFrame.decode(encoded)
        assertEquals(BleFrameType.RESPONSE, frame.type)
        assertContentEquals(payload, frame.payload)
    }

    @Test
    fun `decode throws on too-short input`() {
        assertFailsWith<IllegalArgumentException> { BleFrame.decode(byteArrayOf(0, 0, 0)) }
    }

    @Test
    fun `decode throws on unknown frame type`() {
        // length=0, type=0xFF (unknown)
        assertFailsWith<IllegalArgumentException> {
            BleFrame.decode(byteArrayOf(0, 0, 0, 0, 0xFF.toByte()))
        }
    }

    @Test
    fun `decode throws on length mismatch`() {
        // header says length=5 but no payload follows
        val encoded = byteArrayOf(0, 0, 0, 5, BleFrameType.REQUEST.byte)
        assertFailsWith<IllegalArgumentException> { BleFrame.decode(encoded) }
    }

    @Test
    fun `BleFrameType fromByte returns correct type`() {
        assertEquals(BleFrameType.EXCHANGE, BleFrameType.fromByte(0x01))
        assertEquals(BleFrameType.REQUEST, BleFrameType.fromByte(0x02))
        assertEquals(BleFrameType.RESPONSE, BleFrameType.fromByte(0x03))
        assertEquals(BleFrameType.PING, BleFrameType.fromByte(0x04))
        assertEquals(BleFrameType.PONG, BleFrameType.fromByte(0x05))
    }

    @Test
    fun `BleFrameType fromByte throws on unknown byte`() {
        assertFailsWith<IllegalArgumentException> { BleFrameType.fromByte(0x99.toByte()) }
    }
}
