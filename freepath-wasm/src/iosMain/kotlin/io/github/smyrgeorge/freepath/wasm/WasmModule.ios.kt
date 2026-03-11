@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.smyrgeorge.freepath.wasm

import Wasm3Bridge.Wasm3Bridge
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.dataWithLength
import platform.posix.memcpy

actual fun loadWasmModule(wasmBytes: ByteArray): WasmModule {
    val nsData = wasmBytes.toNSData()
    val bridge = Wasm3Bridge(wasmBytes = nsData, error = null)
    return Wasm3WasmModule(bridge)
}

internal class Wasm3WasmModule(private val bridge: Wasm3Bridge) : WasmModule {
    override fun call(function: String, input: String): String =
        bridge.call(function, input = input)
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    val mutableData = checkNotNull(NSMutableData.dataWithLength(size.toULong()))
    val dest = checkNotNull(mutableData.mutableBytes) { "NSMutableData.mutableBytes is null" }
    usePinned { pinned -> memcpy(dest, pinned.addressOf(0), size.toULong()) }
    return mutableData
}
