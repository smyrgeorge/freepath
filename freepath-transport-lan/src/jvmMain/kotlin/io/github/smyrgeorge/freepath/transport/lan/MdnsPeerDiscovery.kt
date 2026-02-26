package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

class MdnsPeerDiscovery : PeerDiscovery {

    private var jmdns: JmDNS? = null
    private var listener: ServiceListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun start(onPeerDiscovered: suspend (String, String) -> Unit) {
        scope.launch {
            val jm = JmDNS.create()
            jmdns = jm

            val l = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    // Request full resolution; serviceResolved will be called when it completes.
                    jm.requestServiceInfo(event.type, event.name)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    // No action needed — session teardown is handled by the protocol layer.
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info ?: return

                    // Spec: MUST ignore advertisements whose v value is not supported.
                    val version = info.getPropertyString("v") ?: return
                    if (version != SUPPORTED_VERSION) return

                    val nodeId = info.getPropertyString("nodeId") ?: return

                    // Prefer IPv4; fall back to IPv6 if no IPv4 address is available.
                    val host = info.inet4Addresses.firstOrNull()?.hostAddress
                        ?: info.inet6Addresses.firstOrNull()?.hostAddress
                        ?: return

                    scope.launch { onPeerDiscovered(nodeId, LanPeerAddress.encode(host, info.port)) }
                }
            }

            listener = l
            jm.addServiceListener(SERVICE_TYPE, l)
        }
    }

    override fun stop() {
        scope.cancel()
        val jm = jmdns
        val l = listener
        jmdns = null
        listener = null
        // JmDNS cleanup is blocking — run on a daemon thread since stop() is non-suspend.
        if (jm != null) {
            val t = Thread {
                runCatching { l?.let { jm.removeServiceListener(SERVICE_TYPE, it) } }
                runCatching { jm.close() }
            }
            t.isDaemon = true
            t.start()
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_freepath._tcp.local."
        const val SUPPORTED_VERSION = "1"
    }
}
