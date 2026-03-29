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

@OptIn(ExperimentalAtomicApi::class)
class RpcManager<R>(private val timeout: Duration = 30.seconds) {

    private val log = Logger.of(this::class)

    private val mutex = Mutex()
    private val channels = HashMap<Long, Channel<R>>(64)

    suspend fun request(
        reqId: Long,
        timeout: Duration = this.timeout,
        send: suspend () -> Unit
    ): R {
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

    suspend fun request(
        timeout: Duration = this.timeout,
        send: suspend (Long) -> Unit
    ): R {
        val reqId = nextReqId.fetchAndIncrement()
        return request(reqId, timeout) { send(reqId) }
    }

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

    private suspend fun cancel(
        reqId: Long,
        channel: Channel<R>,
        e: TimeoutCancellationException?
    ) {
        mutex.withLock { channels.remove(reqId) }
        channel.cancel(e)
    }

    private suspend fun close(reqId: Long) {
        mutex.withLock { channels.remove(reqId)?.close() }
    }

    companion object {
        private val nextReqId = AtomicLong(0)
    }
}