import SwiftUI

/// The AirDrop-style centrepiece: the paired Android phone as an avatar bubble with soft
/// ripples behind it. While sending, a ring fills around the avatar; when done it turns
/// green, a ✓ badge springs in, and the status line reads "Sent".
struct PeerRadar: View {
    let name: String
    let reachable: Bool
    let halo: HaloState?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var progress: Double {
        switch halo?.phase {
        case .sending(let p): p
        case .done: 1
        default: 0
        }
    }
    private var isDone: Bool { halo?.phase == .done }
    private var isBusy: Bool { halo != nil }

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                if !isBusy && !reduceMotion { Ripples(slow: reachable) }
                Circle()
                    .stroke(Color(.systemFill), lineWidth: 4)
                    .opacity(isBusy ? 1 : 0)
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(isDone ? Color.green : Color.accentColor, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.smooth(duration: 0.3), value: progress)
                Avatar(name: name, dimmed: !reachable)
                    .padding(10)
                    .scaleEffect(isDone ? 1.06 : 1)
                    .animation(.spring(response: 0.35, dampingFraction: 0.5), value: isDone)
            }
            .frame(width: 112, height: 112)
            .overlay(alignment: .bottomTrailing) {
                ZStack {
                    if isDone {
                        Image(systemName: "checkmark.circle.fill")
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .green)
                            .font(.system(size: 32))
                            .background(Circle().fill(Color(.systemBackground)).padding(-3))
                            .transition(.scale(scale: 0.2).combined(with: .opacity))
                    } else {
                        Image(systemName: "smartphone")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.primary)
                            .frame(width: 30, height: 30)
                            .background(Circle().fill(Color(.secondarySystemGroupedBackground)).shadow(color: .black.opacity(0.15), radius: 2, y: 1))
                    }
                }
                .offset(x: -4, y: -4)
                .animation(.spring(response: 0.4, dampingFraction: 0.55), value: isDone)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(name)
            .accessibilityValue(statusText)

            VStack(spacing: 3) {
                Text(name).font(.headline)
                HStack(spacing: 6) {
                    Circle().fill(reachable ? Color.green : Color.orange).frame(width: 7, height: 7)
                    Text(statusText)
                        .font(.subheadline)
                        .monospacedDigit()
                        .contentTransition(.numericText(value: progress))
                        .foregroundStyle(isDone ? AnyShapeStyle(Color.green) : AnyShapeStyle(HierarchicalShapeStyle.secondary))
                        .fontWeight(isDone ? .semibold : .regular)
                }
                .animation(.snappy, value: statusText)
            }
        }
        .padding(.vertical, 18)
        .frame(maxWidth: .infinity)
    }

    private var statusText: String {
        switch halo?.phase {
        case .sending(let p): return p >= 0.99 ? "Sending…" : "Sending… \(Int(p * 100))%"
        case .done: return halo?.label ?? "Sent"
        case .failed: return halo?.label ?? "Couldn’t send"
        case nil: return reachable ? "Ready" : "Looking on this Wi-Fi…"
        }
    }
}

struct Avatar: View {
    let name: String
    var dimmed = false

    var body: some View {
        Circle()
            .fill(LinearGradient(colors: [Color(red: 0.35, green: 0.78, blue: 0.98), Color(red: 0.0, green: 0.48, blue: 1.0)],
                                 startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay(
                Text(Self.initials(name))
                    .font(.system(size: 32, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
            )
            .overlay(Circle().strokeBorder(.white.opacity(0.25), lineWidth: 1).blendMode(.overlay))
            .shadow(color: Color.accentColor.opacity(0.35), radius: 12, y: 6)
            .saturation(dimmed ? 0.2 : 1)
    }

    /// "Pixel 9 Pro" -> "P9", "Ana's Galaxy" -> "AG", "OnePlus" -> "ON".
    static func initials(_ name: String) -> String {
        let words = name.replacingOccurrences(of: "’s", with: "").replacingOccurrences(of: "'s", with: "")
            .split(separator: " ").map(String.init)
        guard let first = words.first?.first else { return "?" }
        if words.count > 1, let second = words[1].first {
            let digits = words[1].prefix { $0.isNumber }
            return (String(first) + (digits.isEmpty ? String(second) : String(digits))).uppercased().prefix(2).description
        }
        return String(words[0].prefix(2)).uppercased()
    }
}

/// Three rings expanding from the avatar, staggered; slower once connected.
private struct Ripples: View {
    let slow: Bool

    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            let period = slow ? 5.4 : 3.2
            ZStack {
                ForEach(0..<3, id: \.self) { i in
                    let phase = ((t / period) + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                    Circle()
                        .stroke(Color.accentColor.opacity(0.35 * (1 - phase)), lineWidth: 10 * (1 - phase) + 1)
                        .scaleEffect(0.85 + 1.6 * phase)
                }
            }
        }
        .allowsHitTesting(false)
    }
}
