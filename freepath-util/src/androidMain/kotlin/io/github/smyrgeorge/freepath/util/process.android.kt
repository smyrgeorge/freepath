package io.github.smyrgeorge.freepath.util

import android.os.Process

actual fun exitApplication(status: Int): Unit = Process.killProcess(Process.myPid())
actual val currentPlatform: Platform = Platform.ANDROID
