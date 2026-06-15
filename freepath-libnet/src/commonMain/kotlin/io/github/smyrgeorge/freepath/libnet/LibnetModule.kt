package io.github.smyrgeorge.freepath.libnet

import kotlinx.coroutines.channels.ReceiveChannel

interface LibnetModule {
    val requests: ReceiveChannel<NetRequest>

    fun start(peerId: String)
    fun stop()

    /**
     * PeerIds reachable right now — identified on any transport (LAN via libp2p, or BLE). These are
     * the peers a relay copy can be handed to immediately for mesh forwarding; an empty set means
     * the message can only be queued for later.
     */
    fun onlinePeerIds(): Set<String>

    suspend fun request(
        reqId: Long,
        peerId: String,
        payload: ByteArray,
        onFrameSent: (reqId: Long, frameIndex: Int, frameCount: Int) -> Unit,
    ): Result<ByteArray>

    suspend fun sendResponse(reqId: Long, payload: ByteArray)
    suspend fun sendResponseFailed(reqId: Long, error: String)
}
