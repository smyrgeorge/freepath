@file:OptIn(ExperimentalForeignApi::class)

package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.freepath.libp2p.Libp2pCallback.onEvent
import io.github.smyrgeorge.freepath.libp2p.cinterop.RawLibP2pEvent
import io.github.smyrgeorge.freepath.libp2p.cinterop.libp2p_event_free
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import kotlin.concurrent.AtomicReference

internal object Libp2pCallback {
    fun onEvent(
        handler: (Libp2pEvent) -> Unit,
        kind: UByte,
        reqId: Long,
        peerId: String,
        addr: String,
        value: ByteArray?,
    ) {
        val event = when (kind.toInt()) {
            0 -> Libp2pEvent.PeerConnected(peerId, addr)
            1 -> Libp2pEvent.PeerDisconnected(peerId)
            3 -> Libp2pEvent.NewListenAddr(peerId) // addr stored in peerId field by Rust
            4 -> Libp2pEvent.PeerIdentified(peerId)
            6 -> Libp2pEvent.RequestReceived(reqId, senderId = peerId, recipientId = addr, value ?: ByteArray(0))
            7 -> Libp2pEvent.ResponseReceived(reqId, senderId = peerId, recipientId = addr, value ?: ByteArray(0))
            8 -> Libp2pEvent.RequestFailed(
                reqId,
                senderId = peerId,
                recipientId = addr,
                error = value?.decodeToString() ?: ""
            )

            else -> return
        }
        handler(event)
    }

    /**
     * C FFI entry point: decodes the raw event and forwards to [onEvent].
     */
    val eventDispatcher: CPointer<CFunction<(COpaquePointer?, CPointer<RawLibP2pEvent>?) -> Unit>> =
        staticCFunction { ctx, ev ->
            if (ev == null || ctx == null) return@staticCFunction
            val ref = ctx.asStableRef<AtomicReference<((Libp2pEvent) -> Unit)?>>()
            val handler = ref.get().value ?: run {
                libp2p_event_free(ev)
                return@staticCFunction
            }
            val e = ev.pointed
            val kind = e.kind
            val reqId = e.req_id.toLong()
            val peerId = e.peer_id?.let { ptr ->
                ByteArray(e.peer_id_len.toInt()) { ptr[it].toByte() }.decodeToString()
            } ?: ""
            val addr = e.addr?.let { ptr ->
                ByteArray(e.addr_len.toInt()) { ptr[it].toByte() }.decodeToString()
            } ?: ""
            val value: ByteArray? = e.value?.let { ptr ->
                ByteArray(e.value_len.toInt()) { ptr[it].toByte() }
            }
            libp2p_event_free(ev)
            onEvent(handler, kind, reqId, peerId, addr, value)
        }
}
