package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.debug
import io.github.smyrgeorge.log4k.classic.error
import io.github.smyrgeorge.log4k.classic.info
import io.github.smyrgeorge.log4k.classic.trace
import io.github.smyrgeorge.log4k.classic.warn
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.staticCFunction

internal object Libp2pLogger {
    private val log = Logger.of("freepath-libp2p")

    fun onLog(level: Int, tag: String, message: String) {
        when (level) {
            4 -> log.error { "[$tag] $message" }
            3 -> log.warn { "[$tag] $message" }
            2 -> log.info { "[$tag] $message" }
            1 -> log.debug { "[$tag] $message" }
            else -> log.trace { "[$tag] $message" }
        }
    }

    val iosLogDispatcher: CPointer<CFunction<(UByte, CPointer<UByteVar>?, ULong, CPointer<UByteVar>?, ULong) -> Unit>> =
        staticCFunction { level, tagPtr, tagLen, msgPtr, msgLen ->
            val tag = tagPtr?.let { ptr -> ByteArray(tagLen.toInt()) { ptr[it].toByte() }.decodeToString() } ?: ""
            val msg = msgPtr?.let { ptr -> ByteArray(msgLen.toInt()) { ptr[it].toByte() }.decodeToString() } ?: ""
            onLog(level.toInt(), tag, msg)
        }
}
