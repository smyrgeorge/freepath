package io.github.smyrgeorge.freepath.util

import io.github.smyrgeorge.log4k.RootLogger
import io.github.smyrgeorge.log4k.impl.appenders.simple.SimpleConsoleLoggingAppender

actual fun configureLogging() {
    RootLogger.Logging.appenders.unregisterAll()
    RootLogger.Logging.appenders.register(SimpleConsoleLoggingAppender())
}
