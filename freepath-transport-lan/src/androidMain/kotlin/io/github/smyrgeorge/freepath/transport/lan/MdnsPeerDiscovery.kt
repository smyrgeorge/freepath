package io.github.smyrgeorge.freepath.transport.lan

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.random.Random

// NsdManager APIs require API 16; our actual minSdk is 26. The suppression is needed because
// AGP 9.0's KMP plugin sets minSdk inside kotlin { android { } } which IntelliJ's lint
// inspector does not yet read, so it incorrectly reports the effective minimum as 1.
@SuppressLint("NewApi")
class MdnsPeerDiscovery(
    override val nodeId: String,
    context: Context,
) : PeerDiscovery {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun start(port: Int, onPeerDiscovered: suspend (nodeId: String, address: String) -> Unit) {
        // Register this node's service for other peers to discover.
        val suffix = "%04x".format(Random.nextInt(0x10000))
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Freepath ($suffix)"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("v", PROTOCOL_VERSION)
            setAttribute("nodeId", this@MdnsPeerDiscovery.nodeId)
        }
        val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = regListener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)

        // Discover peers using a serial resolve channel: we send every discovered
        // NsdServiceInfo here and process one at a time. This prevents FAILURE_ALREADY_ACTIVE
        // on pre-API-34 devices which do not support concurrent resolution.
        val resolveChannel = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        scope.launch {
            for (info in resolveChannel) {
                resolveServiceSuspending(info, onPeerDiscovered)
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                scope.launch { resolveChannel.send(serviceInfo) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // No action needed — session teardown is handled by the protocol layer.
            }
        }

        discoveryListener = listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsdManager.discoverServices(
                DiscoveryRequest.Builder(SERVICE_TYPE).build(),
                { it.run() },
                listener,
            )
        } else {
            discoverServicesLegacy(listener)
        }
    }

    /**
     * Resolves a single [NsdServiceInfo] and suspends until the resolution completes (success or
     * failure). Resuming before dispatching [onPeerDiscovered] ensures the resolve slot is freed
     * before the next item is dequeued.
     *
     * On API 34+, uses [NsdManager.registerServiceInfoCallback] (non-deprecated, supports
     * concurrent resolution). On API 26–33, falls back to the legacy [NsdManager.resolveService]
     * which only handles one in-flight resolution at a time — handled by the serial channel in
     * [start].
     */
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
                    // Unregister immediately — we only need the first update.
                    runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                    // Resume first so the next queued service can start resolving immediately.
                    cont.resume(Unit)

                    val version = info.attributes["v"]?.decodeToString() ?: return
                    if (version != SUPPORTED_VERSION) return
                    val peerNodeId = info.attributes["nodeId"]?.decodeToString() ?: return
                    // Prefer IPv4; fall back to any available address.
                    val host = info.hostAddresses
                        .firstOrNull { it is Inet4Address }?.hostAddress
                        ?: info.hostAddresses.firstOrNull()?.hostAddress
                        ?: return

                    scope.launch { onPeerDiscovered(peerNodeId, LanPeerAddress.encode(host, info.port)) }
                }

                override fun onServiceLost() {
                    if (handled.compareAndSet(false, true)) cont.resume(Unit)
                }

                override fun onServiceInfoCallbackUnregistered() {}
            }
            nsdManager.registerServiceInfoCallback(serviceInfo, { it.run() }, callback)
            cont.invokeOnCancellation {
                runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            }
        } else {
            resolveServiceLegacy(serviceInfo, cont, onPeerDiscovered)
        }
    }

    /**
     * Legacy resolution path for API 26–33. [NsdManager.resolveService] is deprecated at API 34
     * with no equivalent non-deprecated alternative on earlier API levels, so the suppression is
     * intentional and scoped to this function alone.
     */
    @Suppress("DEPRECATION")
    private fun resolveServiceLegacy(
        serviceInfo: NsdServiceInfo,
        cont: CancellableContinuation<Unit>,
        onPeerDiscovered: suspend (String, String) -> Unit,
    ) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                cont.resume(Unit)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // Resume first so the next queued service can start resolving immediately.
                cont.resume(Unit)

                val version = serviceInfo.attributes["v"]?.decodeToString() ?: return
                if (version != SUPPORTED_VERSION) return
                val peerNodeId = serviceInfo.attributes["nodeId"]?.decodeToString() ?: return
                // NsdServiceInfo.host is the only option on API 26–33.
                val host = serviceInfo.host?.hostAddress ?: return

                scope.launch { onPeerDiscovered(peerNodeId, LanPeerAddress.encode(host, serviceInfo.port)) }
            }
        })
    }

    /**
     * Legacy discovery path for API 26–33. [NsdManager.discoverServices] with a protocol int
     * is deprecated at API 34 with no equivalent on earlier API levels.
     */
    @Suppress("DEPRECATION")
    private fun discoverServicesLegacy(listener: NsdManager.DiscoveryListener) {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override suspend fun stop() {
        scope.cancel()
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        registrationListener = null
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
    }

    private companion object {
        const val SERVICE_TYPE = "_freepath._tcp."
        const val PROTOCOL_VERSION = "1"
        const val SUPPORTED_VERSION = "1"
    }
}
