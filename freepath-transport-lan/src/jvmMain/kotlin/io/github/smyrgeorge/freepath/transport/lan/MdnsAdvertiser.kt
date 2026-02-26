package io.github.smyrgeorge.freepath.transport.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.random.Random

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class MdnsAdvertiser actual constructor(
    private val nodeId: String,
    private val port: Int,
) {
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    actual suspend fun start() {
        // JmDNS.create() is a blocking call — launch on IO to avoid blocking the coroutine thread.
        withContext(Dispatchers.IO) {
            val jm = JmDNS.create()
            jmdns = jm

            // Instance name: "Freepath (xxxx)" with a random 4-hex-digit suffix to reduce
            // collision probability (spec: 6-transport-lan.md).
            val suffix = "%04x".format(Random.nextInt(0x10000))
            val instanceName = "Freepath ($suffix)"

            val props = HashMap<String, String>()
            props["v"] = PROTOCOL_VERSION
            props["nodeId"] = nodeId

            val info = ServiceInfo.create(SERVICE_TYPE, instanceName, port, 0, 0, props)
            serviceInfo = info
            jm.registerService(info)
        }
    }

    actual suspend fun stop() {
        // JmDNS.create() is a blocking call — launch on IO to avoid blocking the coroutine thread.
        withContext(Dispatchers.IO) {
            serviceInfo?.let { jmdns?.unregisterService(it) }
            jmdns?.close()
            jmdns = null
            serviceInfo = null
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_freepath._tcp.local."
        const val PROTOCOL_VERSION = "1"
    }
}
