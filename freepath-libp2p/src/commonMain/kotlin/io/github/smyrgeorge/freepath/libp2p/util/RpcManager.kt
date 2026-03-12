package io.github.smyrgeorge.freepath.libp2p.util

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
 * Manages Remote Procedure Call (RPC) communication within the system.
 *
 * Provides support for making requests, handling responses, and managing active communication channels
 * with built-in timeout capabilities. This class also ensures proper handling of opened channels by
 * closing or removing them to prevent resource leaks.
 *
 * @param R The type of response that the RPC expects.
 * @param timeout The duration to wait for a request to complete before timing out.
 */
class RpcManager<R>(
    private val timeout: Duration
) {
    val log: Logger = Logger.of(this::class)

    // Map that keeps in track the active (waiting response) channels.
    private val channels = HashMap<Long, Channel<R>>(1_000)

    // Mutex to protect the [channels] Map.
    private val mutex = Mutex()

    /**
     * Sends a request, waits for its response, and manages the lifecycle of the communication channel.
     *
     * @param f The suspendable function to be executed as part of the request.
     * @return The response of the requested type [T], received through the communication channel.
     * @throws TimeoutCancellationException If the request times out.
     * @throws Exception If an error occurs during the execution of the request or response handling.
     */
    internal suspend inline fun <T : R> request(crossinline f: suspend (Long) -> Unit): T {
        @OptIn(ExperimentalAtomicApi::class)
        val reqId = reqId.fetchAndIncrement()

        // Open the channel here.
        val channel: Channel<T> = open(reqId)

        return try {
            val res = withTimeout(timeout) {
                f(reqId)
                // Wait (suspend) the response here.
                channel.receive()
            }
            // Return the response to the client.
            res
        } catch (e: TimeoutCancellationException) {
            cancel(reqId, channel, e)
            throw e
        } catch (e: Exception) {
            cancel(reqId, channel, null)
            throw e
        } finally {
            // Close the channel.
            close(reqId)
        }
    }

    /**
     * Handles sending a response to a specific request and manages potential timeouts or errors.
     *
     * @param reqId The unique identifier for the request to which the response corresponds.
     * @param res The response object of type [R] to be sent to the waiting clients.
     */
    suspend fun response(reqId: Long, res: R) {
        try {
            withTimeout(2.seconds) {
                // Send the response to all waiting clients.
                mutex.withLock { channels[reqId] }?.send(res)
            }
        } catch (e: Exception) {
            log.warn("[$reqId] Could not send response (${e.message})")
            close(reqId)
        }
    }

    private suspend fun <T : R> open(reqId: Long): Channel<T> {
        // Create a channel.
        val channel = Channel<T>()
        // Save the channel to the [channels] Map.
        mutex.withLock {
            @Suppress("UNCHECKED_CAST")
            channels[reqId] = channel as Channel<R>
        }
        // Return the channel to the client.
        return channel
    }

    private suspend fun <T : R> cancel(reqId: Long, channel: Channel<T>, e: TimeoutCancellationException?) {
        mutex.withLock { channels.remove(reqId) }
        channel.cancel(e)
    }

    private suspend fun close(reqId: Long) {
        mutex.withLock {
            channels[reqId]?.let {
                // Close the channel.
                it.close()
                // Remove it from the channel map.
                channels.remove(reqId)
            }
        }
    }

    companion object {
        @OptIn(ExperimentalAtomicApi::class)
        private val reqId = AtomicLong(0)
    }
}
