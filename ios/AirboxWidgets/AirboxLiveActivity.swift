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
                    if let progress = item.progress {
                        DotRing(progress: progress, tint: tint(item), size: 44)
                    } else {
                        Image(systemName: item.kind.symbol).font(.title2).foregroundStyle(tint(item))
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let battery = item.peerBatteryPercent {
                        Label("\(battery)%", systemImage: batterySymbol(battery))
                            .font(.system(.caption, design: .rounded).weight(.medium)).monospacedDigit()
                            .foregroundStyle(.secondary)
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(item.title).font(.system(.headline, design: .rounded)).lineLimit(1)
                        .contentTransition(.interpolate)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 8) {
                        if !item.subtitle.isEmpty {
                            Text(item.subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                        }
                        ActionRow(item: item)
                    }
                }
            } compactLeading: {
                Image(systemName: item.kind.symbol).foregroundStyle(tint(item))
            } compactTrailing: {
                if let progress = item.progress {
                    DotRing(progress: progress, tint: tint(item), size: 22)
                } else {
                    Text(item.compact)
                        .font(.system(.caption, design: .rounded).weight(.semibold)).lineLimit(1)
                        .foregroundStyle(tint(item))
                }
            } minimal: {
                if let progress = item.progress {
                    DotRing(progress: progress, tint: tint(item), size: 22)
                } else {
                    Image(systemName: item.kind.symbol).foregroundStyle(tint(item))
                }
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
                Text(item.title).font(.system(.headline, design: .rounded)).lineLimit(1)
                Spacer()
                if let progress = item.progress {
                    DotRing(progress: progress, tint: tint(item), size: 28)
                } else {
                    Text(item.compact).font(.system(.subheadline, design: .rounded).weight(.semibold))
                }
            }
            if !item.subtitle.isEmpty { Text(item.subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(2) }
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

/// A ring of dots that light up with progress and turn into a ✓ when complete, the
/// in-island echo of the app's halo. Widgets animate between updates with content
/// transitions; there is no per-frame animation inside a Live Activity.
private struct DotRing: View {
    let progress: Double
    let tint: Color
    let size: CGFloat
    private let count = 12

    var body: some View {
        let lit = Int((min(max(progress, 0), 1) * Double(count)).rounded(.down))
        ZStack {
            if progress >= 1 {
                Circle().fill(tint)
                Image(systemName: "checkmark")
                    .font(.system(size: size * 0.48, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white)
                    .transition(.scale.combined(with: .opacity))
            } else {
                ForEach(0..<count, id: \.self) { i in
                    Circle()
                        .fill(i < lit ? tint : Color.white.opacity(0.22))
                        .frame(width: size * 0.15, height: size * 0.15)
                        .offset(y: -size * 0.42)
                        .rotationEffect(.degrees(Double(i) / Double(count) * 360))
                }
                if size >= 40 {
                    Text("\(Int(progress * 100))")
                        .font(.system(size: size * 0.3, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .contentTransition(.numericText(value: progress))
                        .foregroundStyle(.white)
                }
            }
        }
        .frame(width: size, height: size)
        .animation(.spring(response: 0.4, dampingFraction: 0.7), value: progress >= 1)
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
