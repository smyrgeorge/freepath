package io.github.smyrgeorge.freepath.transport.codec

import io.github.smyrgeorge.freepath.transport.model.Frame

object FrameCodec {
    fun encode(frame: Frame): ByteArray = JsonCodec.json.encodeToString(frame).encodeToByteArray()
    fun decode(bytes: ByteArray): Frame = JsonCodec.json.decodeFromString(bytes.decodeToString())
}
