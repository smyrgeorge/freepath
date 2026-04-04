package io.github.smyrgeorge.freepath.libble

import kotlin.uuid.Uuid

internal object BleConstants {
    val FREEPATH_SERVICE_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf10")

    /** Encode a ByteArray as a lowercase hex string. */
    fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    /** Decode a hex string to a ByteArray. */
    fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
