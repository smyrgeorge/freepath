package io.github.smyrgeorge.freepath.wasm

import kotlin.test.Test
import kotlin.test.assertEquals

class WasmModuleTest {

    private fun engine() = loadWasmModule(loadTestWasm())

    @Test
    fun echoRoundTrips() {
        val result = engine().call("echo", "hello world")
        assertEquals("hello world", result)
    }

    @Test
    fun echoEmptyString() {
        val result = engine().call("echo", "")
        assertEquals("", result)
    }

    @Test
    fun reverseProducesJson() {
        val result = engine().call("reverse", "abc")
        assertEquals("""{"reversed":"cba"}""", result)
    }

    @Test
    fun multipleCallsOnSameInstance() {
        val wasm = engine()
        assertEquals("first", wasm.call("echo", "first"))
        assertEquals("second", wasm.call("echo", "second"))
        assertEquals("""{"reversed":"321"}""", wasm.call("reverse", "123"))
    }
}
