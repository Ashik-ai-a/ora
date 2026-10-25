import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// Dynamic Island (iPhone 14 Pro and later) + Lock Screen presentation of an Airbox item.
struct AirboxLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AirboxActivityAttributes.self) { context in
            LockScreenView(item: context.state)
                .activityBackgroundTint(Color(.systemBackground).opacity(0.9))
                .widgetURL(URL(string: "airbox://open"))
        } dynamicIsland: { context in
            let item = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: item.kind.symbol).font(.title2).foregroundStyle(tint(item))
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let battery = item.peerBatteryPercent {
                        Label("\(battery)%", systemImage: batterySymbol(battery)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(item.title).font(.headline).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 8) {
                        if !item.subtitle.isEmpty {
                            Text(item.subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                        }
                        if let progress = item.progress, progress < 1 {
                            ProgressView(value: progress).tint(tint(item))
                        }
                        ActionRow(item: item)
                    }
                }
            } compactLeading: {
                Image(systemName: item.kind.symbol).foregroundStyle(tint(item))
            } compactTrailing: {
                if let progress = item.progress, progress < 1 {
                    ProgressView(value: progress).progressViewStyle(.circular).tint(tint(item))
                } else {
                    Text(item.compact).font(.caption.bold()).lineLimit(1)
                }
            } minimal: {
                Image(systemName: item.kind.symbol).foregroundStyle(tint(item))
            }
            .keylineTint(tint(item))
            .widgetURL(URL(string: "airbox://open"))
        }
    }
}

private struct LockScreenView: View {
    let item: IslandItem

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: item.kind.symbol).foregroundStyle(tint(item))
                Text(item.title).font(.headline).lineLimit(1)
                Spacer()
                Text(item.compact).font(.subheadline.bold())
            }
            if !item.subtitle.isEmpty { Text(item.subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(2) }
            if let progress = item.progress, progress < 1 { ProgressView(value: progress).tint(tint(item)) }
            ActionRow(item: item)
        }
        .padding()
    }
}

/// Buttons run IslandActionIntent: stop ringing, decline a call, media controls, cancel, copy.
private struct ActionRow: View {
    let item: IslandItem

    var body: some View {
        let buttons = item.actions.filter { $0 != .open }
        if !buttons.isEmpty {
            HStack(spacing: 10) {
                ForEach(buttons, id: \.self) { action in
                    Button(intent: IslandActionIntent(action, target: item.id)) {
                        Label(action.label, systemImage: action.symbol)
                            .labelStyle(buttons.count > 2 ? .iconOnly : .titleAndIcon)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(action == .declineCall || action == .cancel ? .red : tint(item))
                }
            }
        }
    }
}

private func tint(_ item: IslandItem) -> Color {
    switch item.kind {
    case .incomingCall: .green
    case .findDevice: .orange
    case .transfer: .blue
    case .clipboard: .mint
    case .nowPlaying: .pink
    }
}

private func batterySymbol(_ percent: Int) -> String {
    switch percent {
    case ..<13: "battery.0percent"
    case ..<38: "battery.25percent"
    case ..<63: "battery.50percent"
    case ..<88: "battery.75percent"
    default: "battery.100percent"
    }
}
