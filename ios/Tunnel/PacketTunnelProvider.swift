import Network
import NetworkExtension

/// A tunnel that only carries DNS. iOS sends every lookup to a fake resolver
/// inside the tunnel; all other traffic bypasses it. Ad domains are answered
/// with 0.0.0.0 on the phone, and other lookups go to a public resolver.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let tunnelAddress = "10.111.222.1"
    private let dnsAddress = "10.111.222.2"
    private let upstreams = ["1.1.1.1", "9.9.9.9", "8.8.8.8"]

    private var blocklist = Blocklist(bundle: Bundle(for: PacketTunnelProvider.self))
    private let lock = NSLock()
    private var queries = 0
    private var blocked = 0
    private var recent: [String] = []

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let ipv4 = NEIPv4Settings(addresses: [tunnelAddress], subnetMasks: ["255.255.255.255"])
        ipv4.includedRoutes = [NEIPv4Route(destinationAddress: dnsAddress, subnetMask: "255.255.255.255")]
        settings.ipv4Settings = ipv4
        let dns = NEDNSSettings(servers: [dnsAddress])
        dns.matchDomains = [""] // send every lookup through the tunnel
        settings.dnsSettings = dns
        settings.mtu = 1500

        setTunnelNetworkSettings(settings) { [weak self] error in
            if error == nil { self?.readPackets() }
            completionHandler(error)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        completionHandler()
    }

    /// The app polls this for the numbers it shows.
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        lock.lock()
        let stats: [String: Any] = ["queries": queries, "blocked": blocked, "domains": blocklist.count, "recent": recent]
        lock.unlock()
        completionHandler?(try? JSONSerialization.data(withJSONObject: stats))
    }

    private func readPackets() {
        packetFlow.readPackets { [weak self] packets, _ in
            guard let self else { return }
            for packet in packets { self.handle(packet) }
            self.readPackets()
        }
    }

    private func handle(_ packet: Data) {
        guard let query = DNSPacket.Query(packet: packet) else { return }
        count { $0.queries += 1 }
        let q = DNSPacket.question(query.dns)
        if let q, blocklist.isBlocked(q.name) {
            remember(q.name)
            write(query.reply(DNSPacket.blockedAnswer(query.dns, q)))
            return
        }
        forward(query, q, upstreamIndex: 0)
    }

    private func forward(_ query: DNSPacket.Query, _ q: DNSPacket.Question?, upstreamIndex: Int) {
        guard upstreamIndex < upstreams.count else { return }
        let conn = NWConnection(host: NWEndpoint.Host(upstreams[upstreamIndex]), port: 53, using: .udp)
        var finished = false
        let finish: ([UInt8]?) -> Void = { [weak self] response in
            guard let self, !finished else { return }
            finished = true
            conn.cancel()
            guard var resp = response else {
                self.forward(query, q, upstreamIndex: upstreamIndex + 1)
                return
            }
            if let q, let target = DNSPacket.cnameTargets(resp).first(where: self.blocklist.isBlocked) {
                self.remember("\(q.name) (via \(target))")
                resp = DNSPacket.blockedAnswer(query.dns, q)
            }
            self.write(query.reply(resp))
        }
        // One serial queue per lookup, so every callback below runs in order.
        let queue = DispatchQueue(label: "dns-forward")
        conn.stateUpdateHandler = { state in
            if case .failed = state { finish(nil) }
        }
        conn.start(queue: queue)
        conn.send(content: Data(query.dns), completion: .contentProcessed { error in
            if error != nil { finish(nil) }
        })
        conn.receiveMessage { data, _, _, error in
            finish(error == nil ? data.map { [UInt8]($0) } : nil)
        }
        queue.asyncAfter(deadline: .now() + 4) { finish(nil) }
    }

    private func write(_ packet: Data) {
        packetFlow.writePackets([packet], withProtocols: [NSNumber(value: AF_INET)])
    }

    private func remember(_ name: String) {
        count { stats in
            stats.blocked += 1
            stats.recent.removeAll { $0 == name }
            stats.recent.insert(name, at: 0)
            if stats.recent.count > 100 { stats.recent.removeLast() }
        }
    }

    private func count(_ update: (PacketTunnelProvider) -> Void) {
        lock.lock()
        update(self)
        lock.unlock()
    }
}
