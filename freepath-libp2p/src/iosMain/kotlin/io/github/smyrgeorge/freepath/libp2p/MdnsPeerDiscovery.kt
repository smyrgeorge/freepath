package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.debug
import io.github.smyrgeorge.log4k.classic.info
import io.github.smyrgeorge.log4k.classic.warn
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dataWithBytes
import platform.Foundation.runMode
import platform.darwin.NSObject
import platform.posix.arc4random
import kotlin.concurrent.Volatile

internal class MdnsPeerDiscovery(private val nodeId: String) {

    private val log = Logger.of(this::class)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var thread: NSThread? = null

    @Volatile
    private var onDiscovered: (suspend (String, String) -> Unit)? = null

    @Volatile
    private var onRemoved: (suspend (String) -> Unit)? = null

    fun start(
        port: Int,
        onPeerDiscovered: suspend (peerId: String, address: String) -> Unit,
        onPeerRemoved: suspend (peerId: String) -> Unit,
    ) {
        log.info("mDNS starting on port $port (nodeId=$nodeId)")
        onDiscovered = onPeerDiscovered
        onRemoved = onPeerRemoved
        val t = NSThread(block = { runLoopMain(port) })
        t.name = "io.github.smyrgeorge.freepath.mdns"
        t.start()
        thread = t
    }

    fun stop() {
        thread?.cancel()
        thread = null
        onDiscovered = null
        onRemoved = null
    }

    private fun runLoopMain(port: Int) {
        val rl = NSRunLoop.currentRunLoop()

        val suffix = (arc4random() and 0xFFFFu).toString(16).padStart(4, '0')
        val svcName = "Freepath ($suffix)"

        val txtDict = mapOf(
            "v" to "1".encodeToByteArray().toNSData(),
            "nodeId" to nodeId.encodeToByteArray().toNSData(),
        )

        @Suppress("UNCHECKED_CAST")
        val txtData = NSNetService.dataFromTXTRecordDictionary(txtDict as Map<Any?, *>)
        val service = NSNetService("local.", "_freepath._tcp.", svcName, port)
        service.setTXTRecordData(txtData)
        service.delegate = MdnsServicePublishDelegate(log)
        service.scheduleInRunLoop(rl, forMode = NSDefaultRunLoopMode)
        service.publish()

        val resolvedPeers = mutableMapOf<String, String>()
        val browserDelegate = MdnsBrowserDelegate(
            rl = rl,
            log = log,
            resolvedPeers = resolvedPeers,
            onDiscovered = { peerId, address ->
                scope.launch { onDiscovered?.invoke(peerId, address) }
            },
            onRemoved = { peerId ->
                scope.launch { onRemoved?.invoke(peerId) }
            },
        )
        val browser = NSNetServiceBrowser()
        browser.delegate = browserDelegate
        browser.scheduleInRunLoop(rl, forMode = NSDefaultRunLoopMode)
        browser.searchForServicesOfType("_freepath._tcp.", inDomain = "local.")

        val currentThread = NSThread.currentThread()
        while (!currentThread.isCancelled()) {
            rl.runMode(
                mode = NSDefaultRunLoopMode,
                beforeDate = NSRunLoop.currentRunLoop().limitDateForMode(NSDefaultRunLoopMode)!!,
            )
        }

        browser.stop()
        browser.removeFromRunLoop(rl, forMode = NSDefaultRunLoopMode)
        service.stop()
        service.removeFromRunLoop(rl, forMode = NSDefaultRunLoopMode)
    }

    private class MdnsServicePublishDelegate(
        private val log: Logger,
    ) : NSObject(), NSNetServiceDelegateProtocol {
        override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
            log.warn("mDNS service publish failed: $didNotPublish")
        }
    }

    private class MdnsBrowserDelegate(
        private val rl: NSRunLoop,
        private val log: Logger,
        private val resolvedPeers: MutableMap<String, String>,
        private val onDiscovered: (String, String) -> Unit,
        private val onRemoved: (String) -> Unit,
    ) : NSObject(), NSNetServiceBrowserDelegateProtocol {

        private val resolving = mutableListOf<NSNetService>()

        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindService: NSNetService,
            moreComing: Boolean,
        ) {
            log.debug("mDNS service found: ${didFindService.name}")
            didFindService.delegate = MdnsServiceResolveDelegate(
                rl = rl,
                log = log,
                resolving = resolving,
                resolvedPeers = resolvedPeers,
                onDiscovered = onDiscovered,
            )
            didFindService.scheduleInRunLoop(rl, forMode = NSDefaultRunLoopMode)
            resolving += didFindService
            didFindService.resolveWithTimeout(5.0)
        }

        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didRemoveService: NSNetService,
            moreComing: Boolean,
        ) {
            val peerId = resolvedPeers.remove(didRemoveService.name) ?: return
            log.debug("mDNS service removed: ${didRemoveService.name} (nodeId=$peerId)")
            onRemoved(peerId)
        }

        override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
            log.warn("mDNS browse failed: $didNotSearch")
        }

        private class MdnsServiceResolveDelegate(
            private val rl: NSRunLoop,
            private val log: Logger,
            private val resolving: MutableList<NSNetService>,
            private val resolvedPeers: MutableMap<String, String>,
            private val onDiscovered: (String, String) -> Unit,
        ) : NSObject(), NSNetServiceDelegateProtocol {

            override fun netServiceDidResolveAddress(sender: NSNetService) {
                cleanup(sender)
                val txtData = sender.TXTRecordData() ?: run {
                    log.debug("mDNS resolved ${sender.name} but no TXT record")
                    return
                }
                val dict = NSNetService.dictionaryFromTXTRecordData(txtData)
                val v = (dict["v"] as? NSData)?.toByteArray()?.decodeToString()
                val peerId = (dict["nodeId"] as? NSData)?.toByteArray()?.decodeToString()
                if (v != SUPPORTED_VERSION || peerId == null) {
                    log.debug("mDNS resolved ${sender.name} but skipped (wrong version or missing nodeId)")
                    return
                }
                val address = resolvedAddress(sender) ?: run {
                    log.debug("mDNS resolved ${sender.name} ($peerId) but no usable address")
                    return
                }
                log.debug("mDNS peer discovered: $peerId at $address")
                resolvedPeers[sender.name] = peerId
                onDiscovered(peerId, address)
            }

            override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
                log.debug("mDNS resolve failed for ${sender.name}: $didNotResolve")
                cleanup(sender)
            }

            private fun cleanup(svc: NSNetService) {
                resolving.removeAll { it === svc }
                svc.stop()
                svc.removeFromRunLoop(rl, forMode = NSDefaultRunLoopMode)
            }

            /**
             * Parses `NSNetService.addresses` (raw Darwin `sockaddr` blobs) into "host:port".
             * Prefers IPv4; falls back to non-link-local IPv6.
             *
             * Darwin sockaddr_in  layout (16 bytes): sa_len(1) sa_family(1) sin_port(2) sin_addr(4) padding(8)
             * Darwin sockaddr_in6 layout (28 bytes): sa_len(1) sa_family(1) sin6_port(2) sin6_flowinfo(4)
             *                                        sin6_addr(16) sin6_scope_id(4)
             * AF_INET=2, AF_INET6=30 (Darwin-specific value).
             */
            private fun resolvedAddress(svc: NSNetService): String? {
                val addrs = svc.addresses ?: return null
                val port = svc.port

                // Prefer IPv4
                for (data in addrs) {
                    val b = (data as? NSData)?.toByteArray() ?: continue
                    if (b.size < 16) continue
                    if ((b[1].toInt() and 0xFF) != 2 /* AF_INET */) continue
                    val ip = (4..7).joinToString(".") { (b[it].toInt() and 0xFF).toString() }
                    return "$ip:$port"
                }

                // Fallback to non-link-local IPv6
                for (data in addrs) {
                    val b = (data as? NSData)?.toByteArray() ?: continue
                    if (b.size < 28) continue
                    if ((b[1].toInt() and 0xFF) != 30 /* AF_INET6 on Darwin */) continue
                    // Skip link-local (fe80::/10) — requires scope ID and can't be dialled across devices
                    if ((b[8].toInt() and 0xFF) == 0xFE && (b[9].toInt() and 0xFF) >= 0x80) continue
                    val ip = (8..23).toList().chunked(2).joinToString(":") { (hi, lo) ->
                        ((b[hi].toInt() and 0xFF) shl 8 or (b[lo].toInt() and 0xFF)).toString(16)
                    }
                    return "[$ip]:$port"
                }
                return null
            }
        }
    }

    companion object {
        private const val SUPPORTED_VERSION = "1"

        private fun NSData.toByteArray(): ByteArray = bytes!!.readBytes(length.toInt())
        private fun ByteArray.toNSData(): NSData =
            usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
    }
}

