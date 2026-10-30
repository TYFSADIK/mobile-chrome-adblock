import AppIntents
import SwiftUI

@main
struct AdBlockApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @ObservedObject private var tunnel = TunnelController.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 14) {
                        Text(tunnel.isOn ? "Blocking ads in Chrome and all apps" : "Off")
                            .foregroundStyle(.secondary)
                        Button {
                            Task { await tunnel.setOn(!tunnel.isOn) }
                        } label: {
                            Text(tunnel.isOn ? "Turn off" : "Turn on")
                                .font(.title3.bold())
                                .frame(maxWidth: .infinity, minHeight: 52)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(tunnel.isOn ? .green : .blue)
                        .disabled(tunnel.isBusy)
                        if tunnel.isOn {
                            Text("\(tunnel.queries.formatted()) lookups, \(tunnel.blocked.formatted()) blocked")
                                .monospacedDigit()
                            if tunnel.domains > 0 {
                                Text("\(tunnel.domains.formatted()) domains on the blocklist")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .padding(.vertical, 6)
                }

                if tunnel.isOn {
                    Section("Recently blocked") {
                        if tunnel.recent.isEmpty {
                            Text("Nothing yet. Open a website in Chrome.").foregroundStyle(.secondary)
                        }
                        ForEach(tunnel.recent, id: \.self) { Text($0).font(.callout) }
                    }
                }

                Section {
                    Text("You can also turn blocking on and off with Siri or the Shortcuts app: \"Turn on AdBlock\".")
                    Text("YouTube video ads and sponsored posts come from the same servers as the content, so no DNS blocker can remove them.")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            .navigationTitle("AdBlock")
        }
        .alert("Couldn't change AdBlock", isPresented: Binding(get: { tunnel.error != nil }, set: { if !$0 { tunnel.error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(tunnel.error ?? "")
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { tunnel.startPolling() } else { tunnel.stopPolling() }
        }
        .onAppear { tunnel.startPolling() }
    }
}

// MARK: - Shortcuts and Siri

struct TurnOnIntent: AppIntent {
    static var title: LocalizedStringResource = "Turn on AdBlock"
    func perform() async throws -> some IntentResult {
        await TunnelController.shared.setOn(true)
        return .result()
    }
}

struct TurnOffIntent: AppIntent {
    static var title: LocalizedStringResource = "Turn off AdBlock"
    func perform() async throws -> some IntentResult {
        await TunnelController.shared.setOn(false)
        return .result()
    }
}

struct AdBlockShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: TurnOnIntent(), phrases: ["Turn on \(.applicationName)"], shortTitle: "Turn on", systemImageName: "shield.fill")
        AppShortcut(intent: TurnOffIntent(), phrases: ["Turn off \(.applicationName)"], shortTitle: "Turn off", systemImageName: "shield.slash")
    }
}
