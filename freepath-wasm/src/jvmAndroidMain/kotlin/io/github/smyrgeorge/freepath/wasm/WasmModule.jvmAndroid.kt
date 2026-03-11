package io.github.smyrgeorge.freepath.wasm

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock

actual fun loadWasmModule(wasmBytes: ByteArray): WasmModule = ChicoryWasmModule(wasmBytes)

internal class ChicoryWasmModule(wasmBytes: ByteArray) : WasmModule, Closeable {

    private val instance: Instance = Instance.builder(Parser.parse(wasmBytes)).build()
    private val memory get() = instance.memory()
    private val lock = ReentrantLock()

    override fun call(function: String, input: String): String {
        lock.lock()
        try {
            val inputBytes = input.encodeToByteArray()

            // 1. Allocate input buffer in WASM
            val ptr = instance.export("wasm_alloc")
                .apply(inputBytes.size.toLong())[0].toInt()

            // 2. Write input into WASM memory
            memory.write(ptr, inputBytes)

            // 3. Call the function — returns output byte length
            val outputLen = instance.export(function)
                .apply(ptr.toLong(), inputBytes.size.toLong())[0].toInt()

            // 4. Get pointer to output buffer
            val outputPtr = instance.export("wasm_result_ptr").apply()[0].toInt()

            // 5. Read output
            val result = if (outputLen > 0)
                memory.readBytes(outputPtr, outputLen).decodeToString()
            else ""

            // 6. Free input buffer
            instance.export("wasm_dealloc").apply(ptr.toLong(), inputBytes.size.toLong())

            return result
        } finally {
            lock.unlock()
        }
    }

    override fun close() { /* Chicory Instance has no close() */
    }
}
