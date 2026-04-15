package io.github.smyrgeorge.freepath.libp2p

internal object Libp2pJni {
    external fun start(
        nodeId: String,
        sigKeyPrivate: ByteArray,
        listenAddr: String,
        relayAddrs: String,
        eventHandle: Long,
    ): Long

    external fun stop(nodeHandle: Long)
    external fun dial(nodeHandle: Long, multiaddr: String)
    external fun sendRequest(nodeHandle: Long, peerId: String, reqId: Long, payload: ByteArray)
    external fun sendResponse(nodeHandle: Long, reqId: Long, payload: ByteArray)
    external fun sendResponseFailed(nodeHandle: Long, reqId: Long, error: String)
}
