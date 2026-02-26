package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.StatefulProtocol
import io.github.smyrgeorge.freepath.transport.codec.Base58
import io.github.smyrgeorge.freepath.transport.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.transport.model.LocalIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class LanLinkAdapterMdnsTest {

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun createIdentity(): LocalIdentity {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val nodeIdRaw = sha256(sigKp.publicKey).copyOf(16)
        return LocalIdentity(nodeIdRaw, sigKp.publicKey, sigKp.privateKey, encKp.publicKey, encKp.privateKey)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun nodeRunnerArgs(self: LocalIdentity, knownPeers: List<LocalIdentity>): List<String> =
        buildList {
            add(self.nodeIdRaw.toHex())
            add(self.sigKeyPrivate.toHex())
            add(self.sigKeyPublic.toHex())
            add(self.encKeyPrivate.toHex())
            add(self.encKeyPublic.toHex())
            knownPeers.forEach { peer ->
                add(peer.nodeIdRaw.toHex())
                add(peer.sigKeyPublic.toHex())
                add(peer.encKeyPublic.toHex())
            }
        }

    private class ChildNode(
        val process: Process,
        val stdoutLines: Channel<String>,
        private val drainJob: Job,
        private val writer: BufferedWriter,
    ) {
        fun stop() {
            drainJob.cancel()
            runCatching { writer.write("STOP\n"); writer.flush() }
            // Give the child a few seconds to shut down cleanly; kill it if it doesn't.
            if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun forkNode(scope: CoroutineScope, args: List<String>): ChildNode {
        val javaExe = "${System.getProperty("java.home")}/bin/java"
        val cp = System.getProperty("java.class.path")
        val cmd = buildList {
            add(javaExe); add("-cp"); add(cp)
            add(NodeRunner::class.java.name)
            addAll(args)
        }
        val process = ProcessBuilder(cmd).redirectErrorStream(false).start()

        // Drain stderr in a daemon thread so the child never blocks on a full pipe.
        Thread.ofVirtual().start { process.errorStream.copyTo(System.err) }

        // Drain stdout into a Channel so the test can receive() lines with timeout.
        val channel = Channel<String>(capacity = 64)
        val drainJob = scope.launch(Dispatchers.IO) {
            InputStreamReader(process.inputStream).buffered().use { reader ->
                for (line in reader.lines()) {
                    channel.trySend(line)
                }
            }
            channel.close()
        }

        return ChildNode(
            process = process,
            stdoutLines = channel,
            drainJob = drainJob,
            writer = BufferedWriter(OutputStreamWriter(process.outputStream)),
        )
    }

    // ---- tests ---------------------------------------------------------------

    @Test
    fun `messages delivered across separate JVM processes`() = runBlocking {
        val identityA = createIdentity()
        val identityB = createIdentity()
        val identityC = createIdentity()
        val allIdentities = listOf(identityA, identityB, identityC)

        val nodeIdA = Base58.encode(identityA.nodeIdRaw)
        val nodeIdB = Base58.encode(identityB.nodeIdRaw)
        val nodeIdC = Base58.encode(identityC.nodeIdRaw)

        // Fork child processes B and C.
        val childB = forkNode(this, nodeRunnerArgs(identityB, allIdentities))
        val childC = forkNode(this, nodeRunnerArgs(identityC, allIdentities))

        try {
            // Wait for each child to announce READY <nodeId> <port>.
            val (_, portB) = withTimeout(15_000) {
                childB.stdoutLines.receive().split(" ").let { it[1] to it[2].toInt() }
            }
            val (_, portC) = withTimeout(15_000) {
                childC.stdoutLines.receive().split(" ").let { it[1] to it[2].toInt() }
            }

            // Build local node A.
            var protocol: StatefulProtocol? = null
            val linkAdapter = LanLinkAdapter(
                nodeId = nodeIdA,
                peerDiscovery = InMemoryDiscovery(),
                onPeerDisconnected = { peerId -> protocol?.closeSession(peerId) },
                isKnownPeer = { peerId -> peerId == nodeIdB || peerId == nodeIdC },
                onConnectionEstablished = { peerId -> protocol?.initiateHandshake(peerId) },
            )
            val protocolA = StatefulProtocol(
                identity = identityA,
                contactLookup = { nodeIdBytes ->
                    allIdentities.firstOrNull { it.nodeIdRaw.contentEquals(nodeIdBytes) }?.sigKeyPublic
                },
                linkAdapter = linkAdapter,
                onFrameReceived = { _, _, _ -> },
            )
            protocol = protocolA
            protocolA.start()

            try {
                // Bootstrap discovery: connect A → B and A → C directly (same mechanism that
                // mDNS triggers, but without relying on multicast timing).
                launch { linkAdapter.connectToDiscoveredPeer(nodeIdB, "127.0.0.1", portB) }
                launch { linkAdapter.connectToDiscoveredPeer(nodeIdC, "127.0.0.1", portC) }

                // Wait for handshakes to complete on A's side.
                withTimeout(10_000) {
                    while (!protocolA.hasSession(nodeIdB) || !protocolA.hasSession(nodeIdC)) {
                        delay(50)
                    }
                }

                val message = "hello from multi-jvm".encodeToByteArray()
                protocolA.send(nodeIdB, message)
                protocolA.send(nodeIdC, message)

                // Verify each child received the message (stdoutLines.receive() is suspending
                // and can be cancelled by withTimeout, unlike blocking readLine()).
                withTimeout(10_000) {
                    val partsB = childB.stdoutLines.receive().split(" ")
                    assertEquals("RECEIVED", partsB[0], "child B: unexpected line prefix")
                    assertEquals(nodeIdA, partsB[1], "child B: sender should be A")
                    assertEquals(
                        "hello from multi-jvm",
                        Base64.decode(partsB[2]).decodeToString(),
                        "child B: payload mismatch",
                    )

                    val partsC = childC.stdoutLines.receive().split(" ")
                    assertEquals("RECEIVED", partsC[0], "child C: unexpected line prefix")
                    assertEquals(nodeIdA, partsC[1], "child C: sender should be A")
                    assertEquals(
                        "hello from multi-jvm",
                        Base64.decode(partsC[2]).decodeToString(),
                        "child C: payload mismatch",
                    )
                }
            } finally {
                protocolA.stop()
            }
        } finally {
            childB.stop()
            childC.stop()
        }
    }
}
