package io.github.smyrgeorge.freepath.util

actual fun exitApplication(status: Int): Unit = kotlin.system.exitProcess(status)
