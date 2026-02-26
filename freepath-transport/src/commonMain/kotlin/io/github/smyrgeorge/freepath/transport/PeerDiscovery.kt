package io.github.smyrgeorge.freepath.transport

/**
 * A peer discovery mechanism: a self-contained component responsible for finding reachable
 * peers on the local medium (LAN, BLE, optical, etc.) and notifying the link adapter when
 * a new peer is available to connect to.
 *
 * [PeerDiscovery] is the pluggable discovery half of a transport. Each [LinkAdapter]
 * implementation is paired with a [PeerDiscovery] implementation that matches its medium:
 * - LAN: mDNS / DNS-SD
 * - BLE: BLE central scanning
 *
 * Implementations are responsible for:
 * - Scanning / listening for peer announcements on the physical medium.
 * - Filtering out unsupported protocol versions (e.g. unknown `v` values in mDNS TXT records).
 * - Invoking `onPeerDiscovered` whenever a new connectable peer is found.
 *
 * Implementations MUST NOT attempt to connect to peers themselves — connection is the
 * responsibility of the [LinkAdapter]. `onPeerDiscovered` may be called multiple times for
 * the same peer (e.g. if the peer re-advertises); the [LinkAdapter] is responsible for
 * deduplicating.
 *
 * Implementations MUST NOT perform authentication or contact-list lookups. That check is
 * performed by the [LinkAdapter] upon receiving a discovery event.
 */
interface PeerDiscovery {

    /**
     * Starts the discovery mechanism and registers [onPeerDiscovered] as the callback to
     * invoke when a new peer is found.
     *
     * [onPeerDiscovered] receives:
     * - `nodeId`: Base58-encoded Node ID extracted from the peer's advertisement.
     * - `address`: Opaque transport address string whose format is defined by the
     *   [PeerDiscovery] / [LinkAdapter] pair. Examples:
     *   - LAN: `"192.168.1.5:7340"` or `"[::1]:7340"` (host:port)
     *   - BLE: `"AA:BB:CC:DD:EE:FF"` (device MAC address)
     *   The [LinkAdapter] is responsible for parsing the address into whatever parameters
     *   its physical connection API requires.
     *
     * This method is non-suspending; implementations that require blocking I/O during setup
     * (e.g. mDNS socket binding) MUST manage their own [kotlinx.coroutines.CoroutineScope]
     * or thread internally. [onPeerDiscovered] is a suspend function and MUST be invoked
     * from a coroutine context.
     */
    fun start(onPeerDiscovered: suspend (nodeId: String, address: String) -> Unit)

    /**
     * Stops the discovery mechanism and releases all associated resources (sockets, scan
     * sessions, threads). After [stop] returns, `onPeerDiscovered` MUST NOT be invoked again.
     */
    fun stop()
}
