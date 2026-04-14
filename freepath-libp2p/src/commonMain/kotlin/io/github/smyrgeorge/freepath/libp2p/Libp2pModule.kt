package io.github.smyrgeorge.freepath.libp2p

expect class Libp2pModule() : AbstractLibp2pModule {
    fun setEventHandler(handler: suspend (Libp2pEvent) -> Unit): Libp2pModule
    override suspend fun start(peerId: String, sigKeyPrivate: ByteArray, listenAddrs: String, contactLookup: (String) -> Boolean)
    override suspend fun stop()
    override suspend fun dial(multiaddr: String)
    override suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray)
    override suspend fun sendResponse(reqId: Long, payload: ByteArray)
    override suspend fun sendResponseFailed(reqId: Long, error: String)
    override fun onFirstListenAddr(port: Int)
}
