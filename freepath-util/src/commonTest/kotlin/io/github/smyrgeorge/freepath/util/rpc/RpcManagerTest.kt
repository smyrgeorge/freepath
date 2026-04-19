package io.github.smyrgeorge.freepath.util.rpc

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RpcManagerTest {

    @Test
    fun request_withExplicitId_returnsMatchingResponse() = runTest {
        val rpc = RpcManager<String>()
        val deferred = async {
            rpc.request(reqId = 1L) { /* no-op send */ }
        }
        launch { rpc.response(1L, "hello") }
        assertEquals("hello", deferred.await())
    }

    @Test
    fun request_autoAssignsId_callbackReceivesId() = runTest {
        val rpc = RpcManager<Int>()
        var assigned: Long = -1
        val deferred = async {
            rpc.request { id ->
                assigned = id
            }
        }
        // wait until send callback ran and id was captured
        while (assigned < 0) yield()
        launch { rpc.response(assigned, 42) }
        assertEquals(42, deferred.await())
    }

    @Test
    fun request_autoAssignedIds_areDistinct() = runTest {
        val rpc = RpcManager<Int>()
        val seen = mutableListOf<Long>()
        repeat(5) {
            val d = async {
                rpc.request { id ->
                    seen += id
                }
            }
            // release this one
            while (seen.size <= it) yield()
            launch { rpc.response(seen.last(), it) }
            d.await()
        }
        assertEquals(seen.toSet().size, seen.size)
    }

    @Test
    fun request_timesOut_whenNoResponse() = runTest {
        val rpc = RpcManager<String>()
        assertFailsWith<TimeoutCancellationException> {
            rpc.request(reqId = 100L, timeout = 50.milliseconds) { /* never responds */ }
        }
    }

    @Test
    fun response_afterTimeout_isSafelyDropped() = runTest {
        val rpc = RpcManager<String>(timeout = 50.milliseconds)
        assertFailsWith<TimeoutCancellationException> {
            rpc.request(reqId = 7L) { /* no-op */ }
        }
        // delivering a late response must not throw
        rpc.response(7L, "late")
    }

    @Test
    fun response_toUnknownReqId_isSafelyDropped() = runTest {
        val rpc = RpcManager<Int>()
        // no crash, no hang
        rpc.response(9999L, 123)
    }

    @Test
    fun request_sendException_isPropagatedAndCleansUp() = runTest {
        val rpc = RpcManager<String>()

        class BoomException : RuntimeException("boom")
        assertFailsWith<BoomException> {
            rpc.request(reqId = 55L) { throw BoomException() }
        }
        // after cleanup, responding to that id must not crash
        rpc.response(55L, "late")
    }

    @Test
    fun concurrentRequests_eachReceivesOwnResponse() = runTest {
        val rpc = RpcManager<String>()
        val n = 10
        val deferreds = (1..n).map { i ->
            async {
                rpc.request(reqId = i.toLong()) { /* no-op */ }
            }
        }
        // fan out responses
        (1..n).forEach { i ->
            launch { rpc.response(i.toLong(), "r$i") }
        }
        val results = deferreds.awaitAll()
        assertEquals((1..n).map { "r$it" }, results)
    }

    @Test
    fun request_sendIsInvokedWithinTimeoutScope() = runTest {
        val rpc = RpcManager<String>()
        var sendCalled = false
        val deferred = async {
            rpc.request(reqId = 200L, timeout = 1.seconds) {
                sendCalled = true
            }
        }
        // give the send callback a turn to run
        yield()
        yield()
        launch { rpc.response(200L, "ok") }
        deferred.await()
        assertNotEquals(false, sendCalled)
    }
}
