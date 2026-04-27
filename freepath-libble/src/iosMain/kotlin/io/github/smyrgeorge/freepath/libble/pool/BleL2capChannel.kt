package io.github.smyrgeorge.freepath.libble.pool

import io.github.smyrgeorge.freepath.libble.CentralManagerHolder
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.debug
import io.github.smyrgeorge.log4k.classic.warn
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBL2CAPChannel
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSStreamStatusNotOpen
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual class BleL2capChannel(
    actual val peripheralId: String,
    private val nativeChannel: CBL2CAPChannel,
) {
    private val log = Logger.of(this::class)
    private val _incoming = MutableSharedFlow<BleFrame>(replay = 0, extraBufferCapacity = 64)
    actual val incoming: Flow<BleFrame> = _incoming.asSharedFlow()

    private val inputStream: NSInputStream
        get() = nativeChannel.inputStream ?: error("L2CAP inputStream is null for $peripheralId")
    private val outputStream: NSOutputStream
        get() = nativeChannel.outputStream ?: error("L2CAP outputStream is null for $peripheralId")

    init {
        log.debug("BleL2capChannel init for $peripheralId")
        runCatching {
            if (inputStream.streamStatus == NSStreamStatusNotOpen) inputStream.open()
            if (outputStream.streamStatus == NSStreamStatusNotOpen) outputStream.open()
            startReadLoop()
        }.onFailure { e ->
            log.warn("BleL2capChannel init failed for $peripheralId: ${e.message}")
            runCatching { inputStream.close() }
            runCatching { outputStream.close() }
            throw e
        }
    }

    private fun startReadLoop() {
        val runner = ReadLoopRunner(_incoming, inputStream, log, peripheralId)
        // Use GCD instead of NSThread.detachNewThreadSelector: Kotlin methods on NSObject
        // subclasses are not automatically registered as ObjC selectors, so selector-based
        // dispatch throws NSInvalidArgumentException at thread init time.
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            runner.start()
        }
    }

    actual suspend fun send(type: BleFrameType, payload: ByteArray) {
        withContext(Dispatchers.IO) {
            val encoded = BleFrame.encode(type, payload)
            var offset = 0
            while (offset < encoded.size) {
                val written = encoded.usePinned { pinned ->
                    outputStream.write(
                        pinned.addressOf(offset).reinterpret<UByteVar>(),
                        (encoded.size - offset).toULong(),
                    )
                }.toInt()
                if (written <= 0) error("L2CAP outputStream write failed for $peripheralId")
                offset += written
            }
        }
    }

    actual suspend fun close() {
        log.debug("BleL2capChannel closing for $peripheralId")
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { inputStream.close() }
            runCatching { outputStream.close() }
        }
    }

    actual companion object {
        /**
         * Open an outbound L2CAP channel to [peripheralId] (CoreBluetooth UUID string) on [psm].
         *
         * Flow:
         * 1. Look up the CBPeripheral in the CBCentralManager's cache via its UUID.
         * 2. Connect to the peripheral (or join an existing connection).
         * 3. Call [CBPeripheral.openL2CAPChannel] once connected.
         * 4. Return the resulting [BleL2capChannel].
         */
        actual suspend fun connect(peripheralId: String, psm: Int): BleL2capChannel =
            suspendCancellableCoroutine { cont ->
                CentralManagerHolder.manager.connectAndOpenL2CAP(peripheralId, psm) { channel, error ->
                    when {
                        error != null ->
                            cont.resumeWithException(
                                IllegalStateException("L2CAP connect failed for $peripheralId: $error")
                            )

                        channel != null ->
                            runCatching { cont.resume(BleL2capChannel(peripheralId, channel)) }
                                .onFailure { /* continuation already cancelled */ }

                        else ->
                            cont.resumeWithException(
                                IllegalStateException(
                                    "Peripheral $peripheralId not found in CBCentralManager cache — " +
                                            "ensure the device has been discovered before initiating an exchange"
                                )
                            )
                    }
                }
                cont.invokeOnCancellation {
                    CentralManagerHolder.manager.cancelConnection(peripheralId)
                }
            }
    }
}
