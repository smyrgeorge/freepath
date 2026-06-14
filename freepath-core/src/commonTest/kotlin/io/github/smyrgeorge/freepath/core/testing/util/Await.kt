package io.github.smyrgeorge.freepath.core.testing.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polls [condition] until it returns true, or fails with a timeout after [timeout].
 *
 * Runs on [Dispatchers.Default] so it uses real (wall-clock) time even inside `runTest`: the node
 * actors and the fake network run on real dispatchers (the actor-system dispatcher / Dispatchers.IO),
 * so `runTest`'s virtual clock would never advance in step with them.
 */
suspend fun awaitUntil(
    timeout: Duration = 10.seconds,
    poll: Duration = 20.milliseconds,
    condition: suspend () -> Boolean,
): Unit = withContext(Dispatchers.Default) {
    withTimeout(timeout) {
        while (!condition()) delay(poll)
    }
}
