package io.github.smyrgeorge.freepath.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessIosTest {

    @Test
    fun currentPlatform_onIos_isIos() {
        assertEquals(Platform.IOS, currentPlatform)
    }
}
