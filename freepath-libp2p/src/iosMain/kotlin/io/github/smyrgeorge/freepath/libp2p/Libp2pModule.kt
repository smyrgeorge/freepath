@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.freepath.libp2p.cinterop.libp2p_dial
import io.github.smyrgeorge.freepath.libp2p.cinterop.libp2p_send_request
import io.github.smyrgeorge.freepath.libp2p.cinterop.libp2p_send_response
import io.github.smyrgeorge.freepath.libp2p.cinterop.libp2p_send_response_failed
import io.github.smyrgeorge.freepath.libp2p.cinterop.libp2p_set_log_callback
import io.github.smyrgeorge.freepath.libp2p.cinterop.libp2p_start
import io.github.smyrgeorge.freepath.libp2p.cinterop.libp2p_stop
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.AtomicReference

actual class Libp2pModule actual constructor() : AbstractLibp2pModule() {

    init {
        libp2p_set_log_callback(Libp2pLogger.iosLogDispatcher)
    }

    private val mutex = Mutex()
    private var nodePtr: COpaquePointer? = null
    private var handlerRef: StableRef<AtomicReference<((Libp2pEvent) -> Unit)?>>? = null

    // The AtomicReference holds the internal dispatcher; its address is pinned across the FFI.
    private val dispatcherRef = AtomicReference<((Libp2pEvent) -> Unit)?>(null)

    private var mdns: MdnsPeerDiscovery? = null

    // null = not started, non-null = started (CAS from null → Unit)
    private val mdnsStarted = AtomicReference<Unit?>(null)

    actual fun setEventHandler(handler: suspend (Libp2pEvent) -> Unit): Libp2pModule {
        appHandler = handler
        return this
    }

    actual override suspend fun start(
        peerId: String,
        sigKeyPrivate: ByteArray,
        listenAddrs: String,
    ) {
        mutex.withLock {
            if (nodePtr != null) return
            mdns = MdnsPeerDiscovery(peerId)
            dispatcherRef.value = ::dispatch
            val ref = StableRef.create(dispatcherRef)
            val ptr = sigKeyPrivate.usePinned { pinned ->
                withContext(dispatcher) {
                    libp2p_start(
                        node_id = peerId,
                        sig_key_private = pinned.addressOf(0).reinterpret(),
                        sig_key_len = sigKeyPrivate.size.convert(),
                        listen_addr = listenAddrs,
                        event_callback = ref.asCPointer(),
                        event_fun = Libp2pCallback.eventDispatcher,
                    )
                }
            }
            if (ptr == null) {
                ref.dispose()
                dispatcherRef.value = null
                mdns = null
                error("libp2p_start returned null")
            }
            handlerRef = ref
            nodePtr = ptr
        }
    }

    actual override suspend fun stop() {
        mutex.withLock {
            val ptr = nodePtr ?: return
            nodePtr = null
            requests.close()
            mdns?.stop()
            mdns = null
            mdnsStarted.value = null
            scope.coroutineContext.cancelChildren()
            handlerRef?.dispose()
            handlerRef = null
            dispatcherRef.value = null
            metrics.close()
            withContext(dispatcher) { libp2p_stop(ptr) }
        }
    }

    actual override suspend fun dial(multiaddr: String) {
        val ptr = mutex.withLock { nodePtr } ?: return
        withContext(dispatcher) { libp2p_dial(ptr, multiaddr) }
    }

    actual override suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray) {
        val ptr = mutex.withLock { nodePtr } ?: return
        payload.usePinned { pinned ->
            withContext(dispatcher) {
                libp2p_send_request(
                    node = ptr,
                    peer_id = peerId,
                    req_id = reqId.toULong(),
                    payload = if (payload.isEmpty()) null else pinned.addressOf(0).reinterpret(),
                    payload_len = payload.size.convert(),
                )
            }
        }
    }

    actual override suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        val ptr = mutex.withLock { nodePtr } ?: return
        payload.usePinned { pinned ->
            withContext(dispatcher) {
                libp2p_send_response(
                    node = ptr,
                    req_id = reqId.toULong(),
                    payload = if (payload.isEmpty()) null else pinned.addressOf(0).reinterpret(),
                    payload_len = payload.size.convert(),
                )
            }
        }
    }

    actual override suspend fun sendResponseFailed(reqId: Long, error: String) {
        val ptr = mutex.withLock { nodePtr } ?: return
        withContext(dispatcher) { libp2p_send_response_failed(node = ptr, req_id = reqId.toULong(), error = error) }
    }

    actual override fun onFirstListenAddr(port: Int) {
        // Only start mDNS once, using the first IPv4 listen address with a real port.
        if (!mdnsStarted.compareAndSet(null, Unit)) return
        val m = mdns ?: return
        scope.launch {
            m.start(
                port = port,
                onPeerDiscovered = { peerId, address ->
                    val multiaddr = lanAddressToMultiaddr(address) ?: return@start
                    scope.launch { dial(multiaddr) }
                    dispatch(Libp2pEvent.MdnsPeerDiscovered(peerId, address))
                },
                onPeerRemoved = { peerId ->
                    dispatch(Libp2pEvent.MdnsPeerExpired(peerId))
                },
            )
        }
    }
}
