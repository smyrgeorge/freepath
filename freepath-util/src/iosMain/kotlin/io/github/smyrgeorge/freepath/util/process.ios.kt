package io.github.smyrgeorge.freepath.util

import kotlin.system.exitProcess

actual fun exitApplication(status: Int): Unit = exitProcess(status)
