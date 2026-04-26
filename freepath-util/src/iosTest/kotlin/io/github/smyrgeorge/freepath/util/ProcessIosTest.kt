package io.github.smyrgeorge.freepath.util

import kotlin.test.Test
import kotlin.test.assertTrue

class ProcessIosTest {

    @Test
    fun currentPlatform_onIos_isIos() {
        assertTrue(currentPlatform == Platform.IOS || currentPlatform == Platform.IOS_SIMULATOR)
    }
}
