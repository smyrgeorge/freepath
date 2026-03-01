import Foundation

/// Bridges Bonjour mDNS peer discovery to Kotlin/Native via ObjC interop.
///
/// Registers the local node as a `_freepath._tcp.` service and browses for peers.
/// NetService and NetServiceBrowser require an active RunLoop, so all operations
/// run on a dedicated background thread.
@objc(MdnsBridge) public class MdnsBridge: NSObject {

    private let nodeId: String
    private static let serviceType = "_freepath._tcp."
    private static let domain      = "local."
    private static let version     = "1"

    private var thread:   Thread?
    private var rl:       RunLoop?
    private var service:  NetService?
    private var browser:  NetServiceBrowser?
    private var callback: ((String?, String?) -> Void)?

    // All NetService objects being resolved, kept alive until resolution completes.
    private var resolving: [NetService] = []

    @objc public init(nodeId: String) {
        self.nodeId = nodeId
        super.init()
    }

    // MARK: - Public API

    /// Start advertising and browsing. `onPeerDiscovered` is called (on an arbitrary
    /// background thread) whenever a new remote node is fully resolved.
    @objc public func start(port: Int32, onPeerDiscovered: @escaping (String?, String?) -> Void) {
        self.callback = onPeerDiscovered

        let t = Thread { [weak self] in self?.runLoopMain(port: port) }
        t.name = "io.github.smyrgeorge.freepath.mdns"
        t.start()
        thread = t
    }

    /// Stop advertising and browsing.
    @objc public func stop() {
        callback = nil
        thread?.cancel()
        thread = nil
    }

    // MARK: - Run-loop thread

    private func runLoopMain(port: Int32) {
        let currentRL = RunLoop.current
        rl = currentRL

        // Register our service
        let suffix = String(format: "%04x", arc4random() & 0xFFFF)
        let name    = "Freepath-\(String(nodeId.prefix(8)))-\(suffix)"
        let svc     = NetService(domain: Self.domain, type: Self.serviceType, name: name, port: port)
        svc.setTXTRecord(makeTXTRecord())
        svc.delegate = self
        svc.schedule(in: currentRL, forMode: .default)
        svc.publish()
        service = svc

        // Browse for peers
        let b = NetServiceBrowser()
        b.delegate = self
        b.schedule(in: currentRL, forMode: .default)
        b.searchForServices(ofType: Self.serviceType, inDomain: Self.domain)
        browser = b

        // Spin until cancelled
        while !Thread.current.isCancelled {
            currentRL.run(mode: .default, before: Date(timeIntervalSinceNow: 0.5))
        }

        // Clean up on this thread so the run loop is still active
        b.stop()
        b.remove(from: currentRL, forMode: .default)
        svc.stop()
        svc.remove(from: currentRL, forMode: .default)
        for pending in resolving {
            pending.stop()
            pending.remove(from: currentRL, forMode: .default)
        }
        resolving.removeAll()
    }

    // MARK: - Helpers

    private func makeTXTRecord() -> Data {
        NetService.data(fromTXTRecord: [
            "v":      Self.version.data(using: .utf8)!,
            "nodeId": nodeId.data(using: .utf8)!,
        ])
    }

    /// Extracts `"host:port"` from the resolved NetService's addresses.
    /// Returns an IPv4 address when available, falls back to IPv6.
    private func resolvedAddress(for svc: NetService) -> String? {
        guard let addrs = svc.addresses, !addrs.isEmpty else { return nil }
        let port = svc.port

        // Prefer IPv4
        for data in addrs {
            let family = data.withUnsafeBytes { $0.load(as: sockaddr.self).sa_family }
            guard family == UInt8(AF_INET) else { continue }
            var sin = data.withUnsafeBytes { $0.load(as: sockaddr_in.self) }
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &sin.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN))
            return "\(String(cString: buf)):\(port)"
        }

        // Fallback to IPv6
        for data in addrs {
            let family = data.withUnsafeBytes { $0.load(as: sockaddr.self).sa_family }
            guard family == UInt8(AF_INET6) else { continue }
            var sin6 = data.withUnsafeBytes { $0.load(as: sockaddr_in6.self) }
            var buf = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            inet_ntop(AF_INET6, &sin6.sin6_addr, &buf, socklen_t(INET6_ADDRSTRLEN))
            return "[\(String(cString: buf))]:\(port)"
        }
        return nil
    }
}

// MARK: - NetServiceBrowserDelegate

extension MdnsBridge: NetServiceBrowserDelegate {

    public func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didFind service: NetService,
        moreComing: Bool
    ) {
        // Schedule on our run loop so delegate callbacks are dispatched there too.
        service.delegate = self
        service.schedule(in: rl ?? RunLoop.current, forMode: .default)
        resolving.append(service)
        service.resolve(withTimeout: 5.0)
    }

    public func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didRemove service: NetService,
        moreComing: Bool
    ) {
        // Disconnection is detected at the TCP level; nothing to do here.
    }

    public func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didNotSearch errorDict: [String: NSNumber]
    ) {
        // Non-fatal; browsing continues when network becomes available.
    }
}

// MARK: - NetServiceDelegate

extension MdnsBridge: NetServiceDelegate {

    public func netServiceDidResolveAddress(_ sender: NetService) {
        defer {
            resolving.removeAll { $0 === sender }
            sender.stop()
            sender.remove(from: rl ?? RunLoop.current, forMode: .default)
        }

        guard let txtData = sender.txtRecordData() else { return }
        let dict = NetService.dictionary(fromTXTRecord: txtData)

        guard
            let vData   = dict["v"],
            let v       = String(data: vData, encoding: .utf8), v == Self.version,
            let idData  = dict["nodeId"],
            let peerId  = String(data: idData, encoding: .utf8),
            peerId != nodeId,
            let address = resolvedAddress(for: sender)
        else { return }

        callback?(peerId, address)
    }

    public func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolving.removeAll { $0 === sender }
        sender.stop()
        sender.remove(from: rl ?? RunLoop.current, forMode: .default)
    }
}
