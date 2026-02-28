package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.random.Random

class MdnsPeerDiscovery(override val nodeId: String) : PeerDiscovery {

    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null
    private var listener: ServiceListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun start(port: Int, onPeerDiscovered: suspend (String, String) -> Unit) =
        withContext(Dispatchers.IO) {
            val jm = JmDNS.create()
            jmdns = jm

            // Register this node's service for other peers to discover.
            val suffix = "%04x".format(Random.nextInt(0x10000))
            val props = HashMap<String, String>()
            props["v"] = PROTOCOL_VERSION
            props["nodeId"] = nodeId
            val info = ServiceInfo.create(SERVICE_TYPE, "Freepath ($suffix)", port, 0, 0, props)
            serviceInfo = info
            jm.registerService(info)

            // Discover peers.
            val l = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    // Request full resolution; serviceResolved will be called when it completes.
                    jm.requestServiceInfo(event.type, event.name)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    // No action needed — session teardown is handled by the protocol layer.
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val resolved = event.info ?: return

                    // Spec: MUST ignore advertisements whose v value is not supported.
                    val version = resolved.getPropertyString("v") ?: return
                    if (version != SUPPORTED_VERSION) return

                    val peerNodeId = resolved.getPropertyString("nodeId") ?: return

                    // Prefer IPv4; fall back to IPv6 if no IPv4 address is available.
                    val host = resolved.inet4Addresses.firstOrNull()?.hostAddress
                        ?: resolved.inet6Addresses.firstOrNull()?.hostAddress
                        ?: return

                    scope.launch { onPeerDiscovered(peerNodeId, LanPeerAddress.encode(host, resolved.port)) }
                }
            }

            listener = l
            jm.addServiceListener(SERVICE_TYPE, l)
        }

    override suspend fun stop() {
        scope.cancel()
        val jm = jmdns
        val l = listener
        val si = serviceInfo
        jmdns = null
        listener = null
        serviceInfo = null
        if (jm != null) {
            withContext(Dispatchers.IO) {
                runCatching { l?.let { jm.removeServiceListener(SERVICE_TYPE, it) } }
                runCatching { si?.let { jm.unregisterService(it) } }
                runCatching { jm.close() }
            }
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_freepath._tcp.local."
        const val PROTOCOL_VERSION = "1"
        const val SUPPORTED_VERSION = "1"
    }
}
