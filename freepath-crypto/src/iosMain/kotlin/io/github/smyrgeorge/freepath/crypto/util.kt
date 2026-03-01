@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.smyrgeorge.freepath.crypto

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableData
import platform.Foundation.dataWithLength
import platform.posix.memcpy

internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    val mutableData = checkNotNull(NSMutableData.dataWithLength(size.toULong()))
    val dest = checkNotNull(mutableData.mutableBytes) { "NSMutableData.mutableBytes is null" }
    usePinned { pinned -> memcpy(dest, pinned.addressOf(0), size.toULong()) }
    return mutableData
}

internal fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val src = checkNotNull(bytes) { "NSData.bytes is null for non-empty data" }
    return ByteArray(len).also { result ->
        result.usePinned { memcpy(it.addressOf(0), src, length) }
    }
}

// Calls an ObjC method that returns nullable NSData + NSError, captures the error,
// and throws a RuntimeException with its description if the call fails.
internal inline fun objcCall(block: (CPointer<ObjCObjectVar<NSError?>>) -> NSData?): NSData =
    memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        block(err.ptr) ?: throw RuntimeException(err.value?.localizedDescription ?: "CryptoBridge call failed")
    }
