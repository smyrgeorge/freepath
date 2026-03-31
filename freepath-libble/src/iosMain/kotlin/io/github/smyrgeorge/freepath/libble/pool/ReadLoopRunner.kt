package io.github.smyrgeorge.freepath.libble.pool

import io.github.smyrgeorge.log4k.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.Foundation.NSInputStream
import platform.Foundation.NSStreamStatusAtEnd
import platform.Foundation.NSStreamStatusClosed
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
internal class ReadLoopRunner(
    private val sink: MutableSharedFlow<BleFrame>,
    private val stream: NSInputStream,
    private val log: Logger,
    private val peripheralId: String,
) : NSObject() {
    fun start() {
        val headerBuf = ByteArray(5)
        runCatching {
            while (true) {
                val status = stream.streamStatus
                if (status == NSStreamStatusClosed || status == NSStreamStatusAtEnd) break
                // Read header: 4-byte BE length + 1-byte type
                var headerRead = 0
                while (headerRead < 5) {
                    val n = headerBuf.usePinned { pinned ->
                        stream.read(pinned.addressOf(headerRead).reinterpret(), (5 - headerRead).toULong())
                    }.toInt()
                    if (n <= 0) return
                    headerRead += n
                }
                val len = ((headerBuf[0].toInt() and 0xFF) shl 24) or
                        ((headerBuf[1].toInt() and 0xFF) shl 16) or
                        ((headerBuf[2].toInt() and 0xFF) shl 8) or
                        (headerBuf[3].toInt() and 0xFF)
                require(len in 0..BleFrame.MAX_PAYLOAD_SIZE) { "Payload size out of bounds: $len" }
                val typeByte = headerBuf[4]
                val payload = ByteArray(len)
                var payloadRead = 0
                while (payloadRead < len) {
                    val n = payload.usePinned { pinned ->
                        stream.read(pinned.addressOf(payloadRead).reinterpret(), (len - payloadRead).toULong())
                    }.toInt()
                    if (n <= 0) return
                    payloadRead += n
                }
                val type = BleFrameType.fromByte(typeByte)
                sink.tryEmit(BleFrame(type, payload))
            }
        }.onFailure { e ->
            log.warn("ReadLoopRunner error for $peripheralId: $e")
        }
    }
}
