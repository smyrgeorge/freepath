package io.github.smyrgeorge.freepath.wasm

/**
 * A loaded WASM module. Call exported functions by name with a string input;
 * receive a string output.
 *
 * WASM module convention — required exports:
 *   wasm_alloc(len: i32) -> i32
 *   wasm_dealloc(ptr: i32, len: i32)
 *   wasm_result_ptr() -> i32
 *   <function>(ptr: i32, len: i32) -> i32   // returns output byte length
 */
interface WasmModule {
    fun call(function: String, input: String): String
}

expect fun loadWasmModule(wasmBytes: ByteArray): WasmModule
