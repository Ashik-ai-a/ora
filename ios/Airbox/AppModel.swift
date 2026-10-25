import SwiftUI
import UIKit

@MainActor
final class AppModel: ObservableObject {
    @Published var peer: PeerConfig? = Keychain.load()
    @Published var reachable = false
    @Published var incomingText: AirboxClient.SharedText?
    @Published var status: String?
    @Published var uploads: [Upload] = []

    struct Upload: Identifiable { let id = UUID(); let name: String; var progress: Double; var done = false; var failed = false }

    private let client = AirboxClient.shared
    private let island = IslandController.shared
    private var feed: Task<Void, Never>?
    private var cursor: Int64 = 0

    var peerName: String { peer?.deviceName ?? "Android" }

    func paired(_ config: PeerConfig) {
        peer = config
        startFeed()
    }

    func unpair() {
        feed?.cancel()
        Keychain.delete()
        peer = nil
        Task { await island.endAll() }
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active: startFeed()
        // iOS suspends the app shortly after this; keep the feed only as long as we're allowed.
        case .background: keepAliveBriefly()
        default: break
        }
    }

    // MARK: Paste & send (the main action)

    /// Called by the system PasteButton, which pastes without the "Allow Paste?" prompt.
    func send(pasted strings: [String]) {
        guard let text = strings.first?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
            status = "The clipboard is empty."
            return
        }
        send(text: text)
    }

    func send(text: String) {
        Task {
            do {
                try await client.sendText(text)
                status = "Sent — paste it on \(peerName)."
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                await island.show(IslandController.clipboardSent(preview: text, peer: peerName))
            } catch {
                status = error.localizedDescription
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            }
        }
    }

    func upload(_ files: [(Data, String, String)]) {
        let batch = UUID().uuidString
        Task {
            for (index, (data, name, mime)) in files.enumerated() {
                let upload = Upload(name: name, progress: 0)
                uploads.insert(upload, at: 0)
                do {
                    try await client.upload(data, name: name, mimeType: mime) { p in
                        Task { @MainActor in
                            self.setProgress(upload.id, p)
                            let overall = (Double(index) + p) / Double(files.count)
                            await self.island.show(IslandController.upload(id: batch, name: name, count: files.count,
                                                                            progress: overall, peer: self.peerName))
                        }
                    }
                    setProgress(upload.id, 1, done: true)
                } catch {
                    if let i = uploads.firstIndex(where: { $0.id == upload.id }) { uploads[i].failed = true }
                    status = error.localizedDescription
                    await island.end(id: "transfer:\(batch)")
                    return
                }
            }
            await island.show(IslandController.upload(id: batch, name: "\(files.count) item(s)", count: files.count,
                                                      progress: 1, peer: peerName, finished: true))
        }
    }

    private func setProgress(_ id: UUID, _ p: Double, done: Bool = false) {
        guard let i = uploads.firstIndex(where: { $0.id == id }) else { return }
        uploads[i].progress = p
        uploads[i].done = done
    }

    func copyIncoming() {
        guard let text = incomingText?.text else { return }
        UIPasteboard.general.string = text
        status = "Copied."
        Task { await island.end(id: "clipboard") }
    }

    // MARK: Live feed from Android → app + Dynamic Island

    private func startFeed() {
        guard peer != nil, feed == nil || feed!.isCancelled else { return }
        feed = Task {
            while !Task.isCancelled {
                do {
                    let page = try await client.events(after: cursor)
                    reachable = true
                    cursor = page.next
                    for event in page.events { await handle(event) }
                } catch {
                    reachable = false
                    if case AirboxError.http(401, _) = error { status = error.localizedDescription; return }
                    try? await Task.sleep(for: .seconds(3))
                }
            }
        }
    }

    private func keepAliveBriefly() {
        var id = UIBackgroundTaskIdentifier.invalid
        id = UIApplication.shared.beginBackgroundTask(withName: "airbox-feed") {
            self.feed?.cancel()
            self.feed = nil
            UIApplication.shared.endBackgroundTask(id)
        }
    }

    private func handle(_ event: AirboxClient.Event) async {
        switch event.type {
        case "text":
            guard let text = try? event.data.decode(AirboxClient.SharedText.self) else { return }
            incomingText = text
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            await island.show(IslandItem(id: "clipboard", kind: .clipboard, compact: "Text", title: "Text from \(peerName)",
                                         subtitle: IslandController.trim(text.text, 60), actions: [.copy], peerName: peerName,
                                         startedAtEpochMs: now, endsAtEpochMs: now + 30_000))
        case "island":
            if let item = try? event.data.decode(IslandItem.self) { await island.show(item) }
        case "island_end":
            struct End: Decodable { let id: String }
            if let end = try? event.data.decode(End.self) { await island.end(id: end.id) }
        default:
            break
        }
    }
}
