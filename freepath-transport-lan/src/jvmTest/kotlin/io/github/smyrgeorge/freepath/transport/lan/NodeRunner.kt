package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.StatefulProtocol
import io.github.smyrgeorge.freepath.transport.codec.Base58
import io.github.smyrgeorge.freepath.transport.model.LocalIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.io.encoding.Base64

object NodeRunner {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val nodeIdRaw = args[0].hexToBytes()
        val sigPrivKey = args[1].hexToBytes()
        val sigPubKey = args[2].hexToBytes()
        val encPrivKey = args[3].hexToBytes()
        val encPubKey = args[4].hexToBytes()

        class Peer(val nodeIdRaw: ByteArray, val nodeIdStr: String, val sigPubKey: ByteArray)

        val knownPeers = mutableListOf<Peer>()
        var i = 5
        while (i + 3 <= args.size) {
            val raw = args[i].hexToBytes()
            knownPeers += Peer(raw, Base58.encode(raw), args[i + 1].hexToBytes())
            i += 3
        }

        val identity = LocalIdentity(nodeIdRaw, sigPubKey, sigPrivKey, encPubKey, encPrivKey)
        val nodeId = Base58.encode(nodeIdRaw)
        val knownNodeIdStrings = knownPeers.map { it.nodeIdStr }.toSet()
        // lateinit-style holder so lambdas below can reference protocol before it is assigned.
        var protocol: StatefulProtocol? = null

        val linkAdapter = LanLinkAdapter(
            nodeId = nodeId,
            peerDiscovery = MdnsPeerDiscovery(),
            onPeerDisconnected = { peerId -> protocol?.closeSession(peerId) },
            isKnownPeer = { peerId -> peerId in knownNodeIdStrings },
            onConnectionEstablished = { peerId -> protocol?.initiateHandshake(peerId) },
        )

        val p = StatefulProtocol(
            identity = identity,
            contactLookup = { nodeIdBytes ->
                knownPeers.firstOrNull { it.nodeIdRaw.contentEquals(nodeIdBytes) }?.sigPubKey
            },
            linkAdapter = linkAdapter,
            onFrameReceived = { peerId, frame, _ ->
                val plaintext = Base64.decode(frame.payload)
                println("RECEIVED $peerId ${Base64.encode(plaintext)}")
                System.out.flush()
            },
        )
        protocol = p
        p.start()

        println("READY $nodeId ${linkAdapter.localPort}")
        System.out.flush()

        val reader = BufferedReader(InputStreamReader(System.`in`))
        loop@ while (true) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            val parts = line.trim().split(" ")
            when (parts.getOrNull(0)) {
                "SEND" -> {
                    if (parts.size < 3) continue@loop
                    val payload = Base64.decode(parts[2])
                    try {
                        p.send(parts[1], payload)
                    } catch (e: Exception) {
                        System.err.println("SEND_ERROR ${e.message}")
                        System.err.flush()
                    }
                }

                "STOP" -> break@loop
            }
        }

        p.stop()
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
