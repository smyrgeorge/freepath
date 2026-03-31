package io.github.smyrgeorge.freepath.libble.l2cap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import io.github.smyrgeorge.freepath.libble.pool.BleL2capChannel
import io.github.smyrgeorge.freepath.util.AndroidContextHolder
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@SuppressLint("NewApi")
@OptIn(ExperimentalAtomicApi::class)
actual class BleL2capServer actual constructor() {

    private val log = Logger.of(this::class)
    private val started = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null

    actual val psm: Int get() = serverSocket?.psm ?: 0

    actual val incoming: Flow<BleL2capChannel> = callbackFlow {
        check(serverSocket != null) { "BleL2capServer not started" }
        val socket = serverSocket!!
        log.debug("L2CAP accept loop started")
        while (isActive) {
            runCatching {
                val client = withContext(Dispatchers.IO) { socket.accept() }
                val peripheralId = client.remoteDevice.address
                log.debug("L2CAP accepted inbound connection from $peripheralId")
                val channel = BleL2capChannel(peripheralId, client)
                trySend(channel)
            }.onFailure { e ->
                if (isActive) log.warn("L2CAP accept error: $e")
            }
        }
        log.debug("L2CAP accept loop ended")
        awaitClose()
    }

    @Suppress("MissingPermission")
    actual suspend fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        val ctx = requireNotNull(AndroidContextHolder.applicationContext) {
            "BleL2capServer: AndroidContextHolder.applicationContext must be set"
        }
        val bt = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        serverSocket = withContext(Dispatchers.IO) {
            bt.adapter.listenUsingInsecureL2capChannel()
        }
        log.info("BleL2capServer started on psm=${serverSocket!!.psm}")
    }

    actual suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        withContext(Dispatchers.IO) { runCatching { serverSocket?.close() } }
        serverSocket = null
        log.info("BleL2capServer stopped")
    }
}
