package io.github.smyrgeorge.freepath.libp2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.random.Random

internal class MdnsPeerDiscovery(val nodeId: String, context: Context) {

    private val log = Logger.of(this::class)
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicastLock: WifiManager.MulticastLock =
        (context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("freepath-mdns")
            .also { it.setReferenceCounted(false) }
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val resolvedPeers = ConcurrentHashMap<String, String>()

    @SuppressLint("NewApi")
    fun start(
        port: Int,
        onPeerDiscovered: suspend (peerId: String, address: String) -> Unit,
        onPeerRemoved: suspend (peerId: String) -> Unit,
    ) {
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        multicastLock.acquire()
        log.info { "mDNS starting on port $port (nodeId=$nodeId)" }
        val suffix = "%04x".format(Random.nextInt(0x10000))
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Freepath ($suffix)"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("v", PROTOCOL_VERSION)
            setAttribute("nodeId", this@MdnsPeerDiscovery.nodeId)
        }
        val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                log.debug { "mDNS service registered: ${info.serviceName}" }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                log.error { "mDNS service registration failed: errorCode=$errorCode" }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                log.debug { "mDNS service unregistered: ${info.serviceName}" }
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                log.warn { "mDNS service unregistration failed: errorCode=$errorCode" }
            }
        }
        registrationListener = regListener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)

        val resolveChannel = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        scope.launch {
            for (info in resolveChannel) {
                resolveServiceSuspending(info, onPeerDiscovered)
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                log.debug { "mDNS discovery started for $serviceType" }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                log.debug { "mDNS discovery stopped for $serviceType" }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log.debug { "mDNS discovery start failed: $serviceType errorCode=$errorCode" }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                log.warn { "mDNS discovery stop failed: $serviceType errorCode=$errorCode" }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                log.debug { "mDNS service found: ${serviceInfo.serviceName}" }
                scope.launch { resolveChannel.send(serviceInfo) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val peerNodeId = resolvedPeers.remove(serviceInfo.serviceName) ?: return
                log.debug { "mDNS service lost: ${serviceInfo.serviceName} (nodeId=$peerNodeId)" }
                scope.launch { onPeerRemoved(peerNodeId) }
            }
        }

        discoveryListener = listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsdManager.discoverServices(DiscoveryRequest.Builder(SERVICE_TYPE).build(), { it.run() }, listener)
        } else {
            @Suppress("DEPRECATION")
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private suspend fun resolveServiceSuspending(
        serviceInfo: NsdServiceInfo,
        onPeerDiscovered: suspend (String, String) -> Unit,
    ) = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val handled = AtomicBoolean(false)
            lateinit var callback: NsdManager.ServiceInfoCallback
            callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    if (handled.compareAndSet(false, true)) cont.resume(Unit)
                }

                override fun onServiceUpdated(info: NsdServiceInfo) {
                    if (!handled.compareAndSet(false, true)) return
                    runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                    cont.resume(Unit)
                    val version = info.attributes["v"]?.decodeToString() ?: return
                    if (version != SUPPORTED_VERSION) return
                    val peerNodeId = info.attributes["nodeId"]?.decodeToString() ?: return
                    val host = info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                        ?: info.hostAddresses.firstOrNull { !it.isLinkLocalAddress }?.hostAddress
                        ?: return
                    resolvedPeers[info.serviceName] = peerNodeId
                    scope.launch { onPeerDiscovered(peerNodeId, LanPeerAddressCodec.encode(host, info.port)) }
                }

                override fun onServiceLost() {
                    if (handled.compareAndSet(false, true)) cont.resume(Unit)
                }

                override fun onServiceInfoCallbackUnregistered() {}
            }
            nsdManager.registerServiceInfoCallback(serviceInfo, { it.run() }, callback)
            cont.invokeOnCancellation { runCatching { nsdManager.unregisterServiceInfoCallback(callback) } }
        } else {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    cont.resume(Unit)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    cont.resume(Unit)
                    val version = serviceInfo.attributes["v"]?.decodeToString() ?: return
                    if (version != SUPPORTED_VERSION) return
                    val peerNodeId = serviceInfo.attributes["nodeId"]?.decodeToString() ?: return
                    val host = serviceInfo.host?.takeIf { !it.isLinkLocalAddress }?.hostAddress ?: return
                    resolvedPeers[serviceInfo.serviceName] = peerNodeId
                    scope.launch { onPeerDiscovered(peerNodeId, LanPeerAddressCodec.encode(host, serviceInfo.port)) }
                }
            })
        }
    }

    fun stop() {
        multicastLock.release()
        scope.cancel()
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    private companion object {
        const val SERVICE_TYPE = "_freepath._tcp."
        const val PROTOCOL_VERSION = "1"
        const val SUPPORTED_VERSION = "1"
    }
}
