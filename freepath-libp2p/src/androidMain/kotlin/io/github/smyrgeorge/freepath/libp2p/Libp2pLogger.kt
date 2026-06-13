package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.debug
import io.github.smyrgeorge.log4k.classic.error
import io.github.smyrgeorge.log4k.classic.info
import io.github.smyrgeorge.log4k.classic.trace
import io.github.smyrgeorge.log4k.classic.warn

internal object Libp2pLogger {
    private val log = Logger.of("freepath-libp2p")

    @JvmStatic
    fun onLog(level: Int, tag: String, message: String) {
        when (level) {
            4 -> log.error { "[$tag] $message" }
            3 -> log.warn { "[$tag] $message" }
            2 -> log.info { "[$tag] $message" }
            1 -> log.debug { "[$tag] $message" }
            else -> log.trace { "[$tag] $message" }
        }
    }
}
