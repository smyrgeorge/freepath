package io.github.smyrgeorge.freepath.wasm

import java.io.File

actual fun loadTestWasm(): ByteArray =
    File(System.getProperty("test.fixtures.wasm")!!).readBytes()
