import ActivityKit
import Foundation

/// Starts, updates and ends one Live Activity per island item.
@MainActor
final class IslandController {
    static let shared = IslandController()

    private var endTasks: [String: Task<Void, Never>] = [:]
    private var lastUpdate: [String: (Date, Double?)] = [:]

    private func activity(for id: String) -> Activity<AirboxActivityAttributes>? {
        Activity<AirboxActivityAttributes>.activities.first { $0.attributes.itemId == id }
    }

    func show(_ item: IslandItem) async {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let content = ActivityContent(state: item, staleDate: item.endsAt)
        if let existing = activity(for: item.id) {
            guard shouldUpdate(item) else { return }
            await existing.update(content)
        } else {
            _ = try? Activity.request(attributes: AirboxActivityAttributes(itemId: item.id), content: content, pushType: nil)
        }
        lastUpdate[item.id] = (Date(), item.progress)
        endTasks[item.id]?.cancel()
        if let endsAt = item.endsAt {
            endTasks[item.id] = Task { [weak self] in
                try? await Task.sleep(for: .seconds(max(0, endsAt.timeIntervalSinceNow)))
                guard !Task.isCancelled else { return }
                await self?.end(id: item.id)
            }
        }
    }

    func end(id: String) async {
        endTasks[id]?.cancel()
        endTasks[id] = nil
        lastUpdate[id] = nil
        await activity(for: id)?.end(nil, dismissalPolicy: .immediate)
    }

    func endAll() async {
        for activity in Activity<AirboxActivityAttributes>.activities { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    /// Same throttle as the Kotlin IslandModel: progress at most once a second unless it jumps 5%.
    private func shouldUpdate(_ item: IslandItem) -> Bool {
        guard let progress = item.progress, let previous = lastUpdate[item.id], let last = previous.1 else { return true }
        return Date().timeIntervalSince(previous.0) >= 1 || progress - last >= 0.05 || progress >= 1
    }

    // MARK: Items the iPhone creates itself (same wording as core/.../live/LiveStatus.kt)

    static func clipboardSent(preview: String, peer: String) -> IslandItem {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return IslandItem(id: "clipboard", kind: .clipboard, compact: "✓", title: "Copied to \(peer)",
                          subtitle: trim(preview, 60), peerName: peer, startedAtEpochMs: now, endsAtEpochMs: now + 4_000)
    }

    static func upload(id: String, name: String, count: Int, progress: Double, peer: String, finished: Bool = false) -> IslandItem {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return IslandItem(
            id: "transfer:\(id)", kind: .transfer, compact: finished ? "✓" : "\(Int(progress * 100))%",
            title: finished ? "Sent to \(peer)" : "To \(peer)" + (count > 1 ? " · \(count) files" : ""),
            subtitle: name, progress: progress, actions: [], peerName: peer, startedAtEpochMs: now,
            endsAtEpochMs: finished ? now + 6_000 : nil)
    }

    static func trim(_ s: String, _ max: Int) -> String {
        s.count <= max ? s : String(s.prefix(max - 1)).trimmingCharacters(in: .whitespaces) + "…"
    }
}
