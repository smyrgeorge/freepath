package io.github.smyrgeorge.freepath.util.rpc

import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Correlates outgoing requests with incoming responses.
 *
 * Two calling patterns are supported:
 * - [request] with a caller-supplied [reqId] (e.g. BLE, where the reqId is part of the wire frame)
 * - [request] without a reqId (auto-generated via an atomic counter; e.g. libp2p)
 *
 * Concurrent requests with distinct reqIds are fully independent.
 */
class RpcManager<R>(private val timeout: Duration = 30.seconds) {

    private val log = Logger.of(this::class)

    private val mutex = Mutex()
    private val channels = HashMap<Long, Channel<R>>(64)

    /**
     * Send a request with a caller-supplied [reqId] and suspend until the response arrives.
     *
     * [send] is invoked after the channel is registered, so no response can be missed.
     */
    suspend fun request(reqId: Long, send: suspend () -> Unit): R {
        val channel = open(reqId)
        return try {
            withTimeout(timeout) {
                send()
                channel.receive()
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

    /**
     * Send a request with an auto-generated [reqId] and suspend until the response arrives.
     *
     * The generated reqId is passed to [send] so it can be embedded in the wire frame.
     */
    @OptIn(ExperimentalAtomicApi::class)
    suspend fun request(send: suspend (Long) -> Unit): R {
        val reqId = nextReqId.fetchAndIncrement()
        return request(reqId) { send(reqId) }
    }

    /** Deliver a response for [reqId] to the suspended [request] call. */
    suspend fun response(reqId: Long, res: R) {
        try {
            withTimeout(2.seconds) {
                mutex.withLock { channels[reqId] }?.send(res)
            }
        } catch (e: Exception) {
            log.warn("[$reqId] Could not deliver response: ${e.message}")
            close(reqId)
        }
    }

    private suspend fun open(reqId: Long): Channel<R> {
        val channel = Channel<R>()
        mutex.withLock { channels[reqId] = channel }
        return channel
    }

    private suspend fun cancel(reqId: Long, channel: Channel<R>, e: TimeoutCancellationException?) {
        mutex.withLock { channels.remove(reqId) }
        channel.cancel(e)
    }

    private suspend fun close(reqId: Long) {
        mutex.withLock { channels.remove(reqId)?.close() }
    }

    companion object {
        @OptIn(ExperimentalAtomicApi::class)
        private val nextReqId = AtomicLong(0)
    }
}