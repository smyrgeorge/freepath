package io.github.smyrgeorge.freepath.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessJvmTest {

    @Test
    fun `currentPlatform on JVM is JVM`() {
        assertEquals(Platform.JVM, currentPlatform)
    }
}
