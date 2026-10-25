import ActivityKit
import AppIntents
import UIKit

/// Buttons on the Dynamic Island / Lock Screen. `LiveActivityIntent` runs in the app's
/// process (iOS launches it in the background if needed), so it can reach the Android phone.
struct IslandActionIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Airbox action"
    static var isDiscoverable = false

    @Parameter(title: "Action") var action: String
    @Parameter(title: "Target") var target: String?

    init() {}
    init(_ action: IslandItem.Action, target: String?) {
        self.action = action.rawValue
        self.target = target
    }

    func perform() async throws -> some IntentResult {
        guard let kind = IslandItem.Action(rawValue: action) else { return .result() }
        switch kind {
        case .copy:
            // Writing the clipboard never prompts; only reading does.
            let shared = try await AirboxClient.shared.sharedText()
            if let text = shared?.text {
                await MainActor.run { UIPasteboard.general.string = text }
            }
            if let target = target { await endActivity(target) }
        case .open:
            break
        default:
            try await AirboxClient.shared.sendAction(kind, target: target)
            let finishes = kind == .stopRing || kind == .declineCall || kind == .cancel
            if finishes, let target = target { await endActivity(target) }
        }
        return .result()
    }
}

/// Ends the activity for an item. Safe in both targets (starting one is app-only).
func endActivity(_ itemId: String) async {
    for activity in Activity<AirboxActivityAttributes>.activities where activity.attributes.itemId == itemId {
        await activity.end(nil, dismissalPolicy: .immediate)
    }
}
