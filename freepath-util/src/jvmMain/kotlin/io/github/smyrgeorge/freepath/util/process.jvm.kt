package io.github.smyrgeorge.freepath.util

import kotlin.system.exitProcess

actual fun exitApplication(status: Int): Unit = exitProcess(status)
actual val currentPlatform: Platform = Platform.JVM
