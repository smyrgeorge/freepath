package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.StatefulProtocol
import io.github.smyrgeorge.freepath.util.Base58
import io.github.smyrgeorge.freepath.transport.model.LocalIdentity
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import kotlin.io.encoding.Base64

object DemoApp {

    private val log = Logger.of(this::class)

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val nodeIndex = (System.getenv("NODE_INDEX") ?: "0").toInt()

        log.info { "╔══════════════════════════════════════════════╗" }
        log.info { "║   Freepath LAN Node — Docker Demo            ║" }
        log.info { "╚══════════════════════════════════════════════╝" }
        log.info { "NODE_INDEX=$nodeIndex" }

        // ── Contact pool ──────────────────────────────────────────────────────
        // Simulates a pre-populated contact book: all demo nodes share the same
        // deterministic identities, as if they had already exchanged contact cards.
        val contactPool = (0..<CONTACT_POOL_SIZE).map { deriveIdentity(it) }
        val identity = contactPool[nodeIndex]
        val nodeId = Base58.encode(identity.nodeIdRaw)
        // nodeId (Base58) → slot index, for human-readable log labels.
        val nodeIdToIndex: Map<String, Int> =
            contactPool.mapIndexed { i, id -> Base58.encode(id.nodeIdRaw) to i }.toMap()

        log.info { "Node ID: $nodeId (slot $nodeIndex)" }

        // ── Build protocol stack ──────────────────────────────────────────────
        var protocol: StatefulProtocol? = null

        val adapter = LanLinkAdapter(
            peerDiscovery = MdnsPeerDiscovery(nodeId),
            onPeerDisconnected = { peerId ->
                val idx = nodeIdToIndex[peerId]
                log.warn { "Peer node-$idx (${peerId.take(12)}...) disconnected" }
                protocol?.closeSession(peerId)
            },
            isKnownPeer = { peerId -> peerId != nodeId && peerId in nodeIdToIndex },
            onConnectionEstablished = { peerId ->
                val idx = nodeIdToIndex[peerId]
                log.info { "TCP connected to node-$idx (${peerId.take(12)}...) — starting handshake" }
                protocol?.initiateHandshake(peerId)
            },
            onIdleTimeout = { peerId ->
                val idx = nodeIdToIndex[peerId]
                log.info { "Idle timeout for node-$idx (${peerId.take(12)}...) — sending CLOSE" }
                protocol?.closeSession(peerId)
            },
        )

        val proto = StatefulProtocol(
            identity = identity,
            contactLookup = { nodeIdBytes ->
                contactPool.firstOrNull { it.nodeIdRaw.contentEquals(nodeIdBytes) }?.sigKeyPublic
            },
            linkAdapter = adapter,
            onFrameReceived = { peerId, frame, _ ->
                val text = Base64.decode(frame.payload).decodeToString()
                val idx = nodeIdToIndex[peerId]
                log.info { "◀ RECEIVED from node-$idx (${peerId.take(12)}...): \"$text\"" }
            },
        )
        protocol = proto

        // ── Graceful shutdown on SIGTERM / SIGINT ─────────────────────────────
        val mainJob = coroutineContext[Job]!!
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info { "Shutdown signal — stopping node..." }
            runBlocking { proto.stop() }
            mainJob.cancel()
        })

        proto.start()
        log.info { "Node started — listening on port ${adapter.localPort} (OS-assigned)" }
        log.info { "Waiting for mDNS peer discovery..." }

        // ── Periodic heartbeats ───────────────────────────────────────────────
        var tick = 0
        while (isActive) {
            delay(10_000)
            tick++

            // ── Connected peers summary ───────────────────────────────────────
            val activePeers = nodeIdToIndex.keys.filter { it != nodeId && proto.hasSession(it) }
            if (activePeers.isEmpty()) {
                log.info { "Connected peers: none" }
            } else {
                val names = activePeers.joinToString { "node-${nodeIdToIndex[it]}" }
                log.info { "Connected peers: $names (${activePeers.size} online)" }
            }

            for (peerId in activePeers) {
                val idx = nodeIdToIndex[peerId]
                val msg = "Heartbeat #$tick from node-$nodeIndex"
                try {
                    proto.send(peerId, msg.encodeToByteArray())
                    log.info { "▶ SENT heartbeat #$tick → node-$idx" }
                } catch (e: Exception) {
                    log.warn { "Send failed to node-$idx: ${e.message}" }
                }
            }
        }
    }

    /**
     * Maximum number of demo identities in the shared contact pool.
     * Any subset of nodes 0..<CONTACT_POOL_SIZE can run simultaneously without
     * reconfiguration — the pool simulates a pre-exchanged contact book.
     */
    private const val CONTACT_POOL_SIZE = 20

    /**
     * Derives a deterministic Ed25519 + X25519 key pair for [index] using only the JDK (no BouncyCastle).
     *
     * Algorithm: SHA-256("freepath-demo-node-<index>") seeds a SHA1PRNG instance which drives
     * the JDK [KeyPairGenerator] for Ed25519 then X25519 in sequence.  Key byte offsets
     * (stable for OpenJDK 21, same structure for both Ed25519 and X25519):
     *   X.509 SubjectPublicKeyInfo  — 12-byte OID header + 32-byte raw public key
     *   PKCS#8 PrivateKeyInfo       — 16-byte DER header + 32-byte raw seed
     */
    private fun deriveIdentity(index: Int): LocalIdentity {
        val seed = MessageDigest.getInstance("SHA-256")
            .digest("freepath-demo-node-$index".toByteArray())

        val sr = SecureRandom.getInstance("SHA1PRNG")
        sr.setSeed(seed)

        val kpg = KeyPairGenerator.getInstance("Ed25519")
        kpg.initialize(NamedParameterSpec.ED25519, sr)
        val kp = kpg.generateKeyPair()

        val pubKey = kp.public.encoded.copyOfRange(12, 44)
        val privKey = kp.private.encoded.copyOfRange(16, 48)
        val nodeIdRaw = MessageDigest.getInstance("SHA-256").digest(pubKey).copyOf(16)

        // Continue from the same PRNG state to derive a deterministic X25519 key pair.
        val kpgX = KeyPairGenerator.getInstance("XDH")
        kpgX.initialize(NamedParameterSpec.X25519, sr)
        val kpX = kpgX.generateKeyPair()

        val encPubKey = kpX.public.encoded.copyOfRange(12, 44)
        val encPrivKey = kpX.private.encoded.copyOfRange(16, 48)

        return LocalIdentity(nodeIdRaw, pubKey, privKey, encPubKey, encPrivKey)
    }
}
