package io.github.smyrgeorge.freepath.core.testing.util

import io.github.smyrgeorge.freepath.core.testing.cluster.TestCluster
import io.github.smyrgeorge.freepath.util.Platform
import io.github.smyrgeorge.freepath.util.currentPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Test entry point that boots a [TestCluster] of [nodes] nodes, runs [body] against it, and always
 * shuts it down. Use as the body of a `@Test`:
 *
 * ```
 * @Test fun `direct delivery`() = clusterTest { cluster ->
 *     val (a, b) = cluster.nodes
 *     ...
 * }
 * ```
 *
 * The cluster framework is JVM-only (in-memory SQLite + `LIBP2P` supported / `LIBBLE` not), so this
 * no-ops on the other targets that also compile this common source set.
 *
 * The whole lifecycle runs on [Dispatchers.Default]: the nodes' actors and the fake network run on
 * real dispatchers, and mixing runTest's virtual-time scheduler with them makes `ActorSystem`
 * shutdown polling busy-spin and hang. ([awaitUntil] already hops to `Dispatchers.Default`.)
 *
 * [body] is bounded by [timeout] as a backstop: if it ever hangs (a regression, a never-satisfied
 * await), the test fails fast with a `TimeoutCancellationException` instead of running forever, and
 * the cluster is still shut down. (`TestCluster.start` is already bounded by its own readiness wait.)
 */
fun clusterTest(
    nodes: Int = 2,
    timeout: Duration = 30.seconds,
    body: suspend (TestCluster) -> Unit,
) = runTest {
    if (currentPlatform != Platform.JVM) return@runTest
    withContext(Dispatchers.Default) {
        val cluster = TestCluster.start(nodes)
        try {
            withTimeout(timeout) { body(cluster) }
        } finally {
            cluster.shutdown()
        }
    }
}
