import ActivityKit
import Foundation

/// Mirrors `app.airbox.core.live.IslandItem` (Kotlin). The Android phone sends these as
/// JSON on the event feed; the iPhone app builds its own for things it does itself.
struct IslandItem: Codable, Hashable, Identifiable {
    var id: String
    var kind: Kind
    var compact: String
    var title: String
    var subtitle: String = ""
    var progress: Double? = nil
    var actions: [Action] = []
    var peerName: String
    var peerBatteryPercent: Int? = nil
    var startedAtEpochMs: Int64
    var endsAtEpochMs: Int64? = nil

    enum Kind: String, Codable, Hashable {
        case incomingCall = "INCOMING_CALL", findDevice = "FIND_DEVICE", transfer = "TRANSFER"
        case clipboard = "CLIPBOARD", nowPlaying = "NOW_PLAYING"

        var symbol: String {
            switch self {
            case .incomingCall: "phone.fill"
            case .findDevice: "bell.and.waves.left.and.right.fill"
            case .transfer: "arrow.up.arrow.down.circle.fill"
            case .clipboard: "doc.on.clipboard.fill"
            case .nowPlaying: "music.note"
            }
        }
    }

    enum Action: String, Codable, Hashable {
        case cancel = "CANCEL", declineCall = "DECLINE_CALL", stopRing = "STOP_RING"
        case playPause = "PLAY_PAUSE", next = "NEXT", previous = "PREVIOUS", copy = "COPY", open = "OPEN"

        var label: String {
            switch self {
            case .cancel: "Cancel"
            case .declineCall: "Decline"
            case .stopRing: "Stop"
            case .playPause: "Play/Pause"
            case .next: "Next"
            case .previous: "Back"
            case .copy: "Copy"
            case .open: "Open"
            }
        }

        var symbol: String {
            switch self {
            case .cancel: "xmark"
            case .declineCall: "phone.down.fill"
            case .stopRing: "bell.slash.fill"
            case .playPause: "playpause.fill"
            case .next: "forward.fill"
            case .previous: "backward.fill"
            case .copy: "doc.on.doc"
            case .open: "arrow.up.forward.app"
            }
        }
    }

    var endsAt: Date? { endsAtEpochMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) } }
}

/// One Live Activity per island item. The content state *is* the item.
struct AirboxActivityAttributes: ActivityAttributes {
    typealias ContentState = IslandItem
    var itemId: String
}
