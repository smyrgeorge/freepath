package io.github.smyrgeorge.freepath.libble.rpc

import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Correlates outgoing BLE request frames with incoming response notifications.
 *
 * The caller supplies the [reqId]; this manager opens a channel keyed by it, suspends
 * until the matching response notification arrives (via [receive]), then returns the payload.
 *
 * Concurrent requests with distinct reqIds are fully independent.
 */
internal class BleRpcManager(private val timeout: Duration = 30.seconds) {

    private val log = Logger.of(this::class)

    private val mutex = Mutex()
    private val channels = HashMap<Long, Channel<Result<ByteArray>>>(64)

    /**
     * Send a request and suspend until the response arrives or [timeout] elapses.
     *
     * [send] is called after the channel is registered, so no response can be missed.
     */
    suspend fun request(reqId: Long, send: suspend () -> Unit): ByteArray {
        val channel = open(reqId)
        return try {
            withTimeout(timeout) {
                send()
                channel.receive().getOrThrow()
            }
        } catch (e: TimeoutCancellationException) {
            cancel(reqId, channel, e)
            throw e
        } catch (e: Exception) {
            cancel(reqId, channel, null)
            throw e
        } finally {
            close(reqId)
        }
    }

    /** Called when a response notification arrives. Routes it to the waiting [request] call. */
    suspend fun receive(reqId: Long, result: Result<ByteArray>) {
        try {
            withTimeout(2.seconds) {
                mutex.withLock { channels[reqId] }?.send(result)
            }
        } catch (e: Exception) {
            log.warn("[$reqId] Could not deliver BLE response: ${e.message}")
            close(reqId)
        }
    }

    private suspend fun open(reqId: Long): Channel<Result<ByteArray>> {
        val channel = Channel<Result<ByteArray>>()
        mutex.withLock { channels[reqId] = channel }
        return channel
    }

    private suspend fun cancel(reqId: Long, channel: Channel<Result<ByteArray>>, e: TimeoutCancellationException?) {
        mutex.withLock { channels.remove(reqId) }
        channel.cancel(e)
    }

    private suspend fun close(reqId: Long) {
        mutex.withLock {
            channels.remove(reqId)?.close()
        }
    }
}
