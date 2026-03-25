package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.random.Random

internal class MdnsPeerDiscovery(val nodeId: String) {

    private val log = Logger.of(this::class)
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null
    private var listener: ServiceListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val resolvedPeers = ConcurrentHashMap<String, String>()

    suspend fun start(
        port: Int,
        onPeerDiscovered: suspend (peerId: String, address: String) -> Unit,
        onPeerRemoved: suspend (peerId: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        log.info { "mDNS starting on port $port (nodeId=$nodeId)" }
        val localAddress = NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
        log.debug { "mDNS binding to $localAddress" }
        val jm = if (localAddress != null) JmDNS.create(localAddress, "freepath", 100) else JmDNS.create()
        jmdns = jm

        val suffix = "%04x".format(Random.nextInt(0x10000))
        val props = mapOf("v" to PROTOCOL_VERSION, "nodeId" to nodeId)
        val info = ServiceInfo.create(SERVICE_TYPE, "Freepath ($suffix)", port, 0, 0, props)
        serviceInfo = info
        jm.registerService(info)
        log.debug { "mDNS service registered: ${info.name} on ${jm.inetAddress}" }

        val l = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                log.debug { "mDNS service found: ${event.name}" }
                jm.requestServiceInfo(event.type, event.name)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val peerNodeId = resolvedPeers.remove(event.name) ?: return
                log.debug { "mDNS service removed: ${event.name} (nodeId=$peerNodeId)" }
                scope.launch { onPeerRemoved(peerNodeId) }
            }

            override fun serviceResolved(event: ServiceEvent) {
                val resolved = event.info ?: return
                log.debug { "mDNS service resolved: ${event.name} host=${resolved.inet4Addresses.firstOrNull()} port=${resolved.port}" }
                val version = resolved.getPropertyString("v") ?: return
                if (version != SUPPORTED_VERSION) return
                val peerNodeId = resolved.getPropertyString("nodeId") ?: return
                val host = resolved.inet4Addresses.firstOrNull()?.hostAddress
                    ?: resolved.inet6Addresses.firstOrNull { !it.isLinkLocalAddress }?.hostAddress
                    ?: return
                resolvedPeers[event.name] = peerNodeId
                scope.launch { onPeerDiscovered(peerNodeId, LanPeerAddressCodec.encode(host, resolved.port)) }
            }
        }

        listener = l
        jm.addServiceListener(SERVICE_TYPE, l)
    }

    suspend fun stop() {
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
