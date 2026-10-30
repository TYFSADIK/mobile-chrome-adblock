import Foundation
import NetworkExtension

/// Owns the VPN configuration that runs the on-device DNS filter.
@MainActor
final class TunnelController: ObservableObject {
    static let shared = TunnelController()

    @Published private(set) var isOn = false
    @Published private(set) var isBusy = false
    @Published private(set) var queries = 0
    @Published private(set) var blocked = 0
    @Published private(set) var domains = 0
    @Published private(set) var recent: [String] = []
    @Published var error: String?

    private var manager: NETunnelProviderManager?
    private var timer: Timer?

    private init() {
        NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.refreshStatus() }
        }
        Task { await load() }
    }

    func setOn(_ on: Bool) async {
        isBusy = true
        defer { isBusy = false }
        do {
            let m = try await loadOrCreate()
            m.isEnabled = true
            // On-demand keeps blocking on across restarts and network changes until turned off.
            m.isOnDemandEnabled = on
            try await m.saveToPreferences()
            try await m.loadFromPreferences()
            if on {
                try m.connection.startVPNTunnel()
            } else {
                m.connection.stopVPNTunnel()
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func startPolling() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.fetchStats() }
        }
        fetchStats()
    }

    func stopPolling() {
        timer?.invalidate()
        timer = nil
    }

    private func load() async {
        manager = try? await NETunnelProviderManager.loadAllFromPreferences().first
        refreshStatus()
    }

    private func loadOrCreate() async throws -> NETunnelProviderManager {
        if let manager { return manager }
        if let existing = try await NETunnelProviderManager.loadAllFromPreferences().first {
            manager = existing
            return existing
        }
        let m = NETunnelProviderManager()
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = (Bundle.main.bundleIdentifier ?? "com.tyfsadik.adblock") + ".tunnel"
        proto.serverAddress = "On this iPhone"
        m.protocolConfiguration = proto
        m.localizedDescription = "AdBlock"
        m.onDemandRules = [NEOnDemandRuleConnect()]
        manager = m
        return m
    }

    private func refreshStatus() {
        let status = manager?.connection.status ?? .disconnected
        isOn = status == .connected || status == .connecting || status == .reasserting
    }

    private func fetchStats() {
        guard isOn, let session = manager?.connection as? NETunnelProviderSession else { return }
        try? session.sendProviderMessage(Data()) { [weak self] data in
            guard let data, let stats = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            Task { @MainActor in
                self?.queries = stats["queries"] as? Int ?? 0
                self?.blocked = stats["blocked"] as? Int ?? 0
                self?.domains = stats["domains"] as? Int ?? 0
                self?.recent = stats["recent"] as? [String] ?? []
            }
        }
    }
}
