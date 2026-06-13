package io.github.smyrgeorge.freepath.libble.pool

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.smyrgeorge.freepath.util.AndroidContextHolder
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.debug
import io.github.smyrgeorge.log4k.classic.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException

actual class BleL2capChannel(
    actual val peripheralId: String,
    private val socket: BluetoothSocket,
) {
    private val log = Logger.of(this::class)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _incoming = MutableSharedFlow<BleFrame>(replay = 0, extraBufferCapacity = 64)
    actual val incoming: Flow<BleFrame> = _incoming.asSharedFlow()

    init {
        log.debug("BleL2capChannel init for $peripheralId")
        scope.launch { readLoop() }
    }

    private suspend fun readLoop() =
        withContext(Dispatchers.IO) {
            val dis = DataInputStream(socket.inputStream)
            runCatching {
                while (true) {
                    // Read 4-byte BE length.
                    val len = dis.readInt()
                    require(len in 0..BleFrame.MAX_PAYLOAD_SIZE) { "Payload size out of bounds: $len" }
                    // Read 1-byte type.
                    val typeByte = dis.readByte()
                    // Read payload.
                    val payload = ByteArray(len).also { if (len > 0) dis.readFully(it) }
                    val type = BleFrameType.fromByte(typeByte)
                    _incoming.tryEmit(BleFrame(type, payload))
                }
            }.onFailure { e ->
                if (e !is EOFException) {
                    log.warn("BleL2capChannel read loop error for $peripheralId: $e")
                }
            }
            scope.cancel()
        }

    actual suspend fun send(type: BleFrameType, payload: ByteArray) {
        withContext(Dispatchers.IO) {
            // OutputStream.write() guarantees full write or throws — no partial-write loop needed
            // (unlike iOS NSOutputStream which may write fewer bytes than requested).
            socket.outputStream.write(BleFrame.encode(type, payload))
            socket.outputStream.flush()
        }
    }

    actual suspend fun close() {
        log.debug("BleL2capChannel closing for $peripheralId")
        scope.cancel()
        withContext(NonCancellable + Dispatchers.IO) { runCatching { socket.close() } }
    }

    actual companion object {
        private val log = Logger.of(BleL2capChannel::class)

        @SuppressLint("NewApi")
        actual suspend fun connect(peripheralId: String, psm: Int): BleL2capChannel =
            withContext(Dispatchers.IO) {
                log.debug("BleL2capChannel connecting to $peripheralId psm=$psm")
                val ctx = requireNotNull(AndroidContextHolder.applicationContext) {
                    "BleL2capChannel: AndroidContextHolder.applicationContext must be set"
                }
                val bt = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val device = bt.adapter.getRemoteDevice(peripheralId)
                val socket = device.createInsecureL2capChannel(psm)
                socket.connect()
                log.debug("BleL2capChannel connected to $peripheralId")
                BleL2capChannel(peripheralId, socket)
            }
    }
}
