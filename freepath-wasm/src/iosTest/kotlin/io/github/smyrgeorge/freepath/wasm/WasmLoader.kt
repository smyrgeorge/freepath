@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.smyrgeorge.freepath.wasm

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

actual fun loadTestWasm(): ByteArray {
    val path = "${NSBundle.mainBundle.bundlePath}/echo.wasm"
    val f = fopen(path, "rb") ?: error("Cannot open $path")
    try {
        fseek(f, 0, SEEK_END)
        val size = ftell(f).toInt()
        fseek(f, 0, SEEK_SET)
        return ByteArray(size).also { buf ->
            buf.usePinned { fread(it.addressOf(0), 1.convert(), size.convert(), f) }
        }
    } finally {
        fclose(f)
    }
}
