import SwiftUI
import UIKit

/// The sending animation that hugs the Dynamic Island while Airbox is open: a ring of dots
/// traces the island's outline as the transfer progresses, closes into a solid ring, and
/// finishes with a drawn-on ✓ and a success haptic, then sinks back into the island.
///
/// Live Activities can't draw outside the island (the system clips them), so this lives in
/// the app's own window, which can draw anywhere on screen, including around the cutout.
struct HaloState: Equatable {
    enum Phase: Equatable {
        case sending(Double), done, failed
        var isSending: Bool { if case .sending = self { true } else { false } }
    }
    var phase: Phase
    var label: String
}

struct IslandHaloOverlay: View {
    let state: HaloState?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { proxy in
            // proxy.safeAreaInsets is zero here (we ignore the safe area), so ask the window.
            let island = IslandGeometry.rect(safeAreaTop: IslandGeometry.windowSafeTop, width: proxy.size.width)
            ZStack {
                if let state {
                    IslandHalo(state: state, island: island, reduceMotion: reduceMotion)
                        .transition(.asymmetric(
                            insertion: .scale(scale: 0.86, anchor: .center).combined(with: .opacity),
                            removal: .scale(scale: 0.7, anchor: .center).combined(with: .opacity)))
                }
            }
            .animation(.spring(response: 0.45, dampingFraction: 0.8), value: state == nil)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Where the island is. Apple doesn't expose its frame, so this uses the measured sizes of
/// current models, keyed off the top safe-area inset. Phones without an island get a
/// virtual one just below the status bar so the animation still has somewhere to live.
enum IslandGeometry {
    static func rect(safeAreaTop top: CGFloat, width: CGFloat) -> CGRect {
        let size = CGSize(width: 126, height: 37.33)
        let y: CGFloat
        switch top {
        case 62...: y = 14        // iPhone 16 Pro / Pro Max and later
        case 59..<62: y = 11.33   // iPhone 14 Pro – 16 / 16 Plus
        default: y = top + 6      // notch or no island: float under the status bar
        }
        return CGRect(x: (width - size.width) / 2, y: y, width: size.width, height: size.height)
    }

    static var windowSafeTop: CGFloat {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.safeAreaInsets.top ?? 0
    }

    static var hasIsland: Bool { windowSafeTop >= 59 }
}

private struct IslandHalo: View {
    let state: HaloState
    let island: CGRect
    let reduceMotion: Bool

    /// Distance between the island edge and the dots.
    private let gap: CGFloat = 7
    @State private var drawCheck = false
    @State private var pulse = false

    private var progress: Double {
        switch state.phase {
        case .sending(let p): max(0.02, min(1, p))
        case .done, .failed: 1
        }
    }

    private var tint: Color {
        switch state.phase {
        case .sending: Color(red: 0.25, green: 0.56, blue: 1.0)
        case .done: Color(red: 0.19, green: 0.82, blue: 0.35)
        case .failed: Color(red: 1.0, green: 0.27, blue: 0.23)
        }
    }

    var body: some View {
        let ring = island.insetBy(dx: -gap, dy: -gap)
        ZStack(alignment: .topLeading) {
            // Track: faint dots all the way round.
            HaloCapsule()
                .stroke(Color.primary.opacity(0.14), style: dots)
            // Progress: bright dots, clockwise from the bottom centre.
            HaloCapsule()
                .trim(from: 0, to: progress)
                .stroke(tint, style: dots)
                .shadow(color: tint.opacity(0.75), radius: 4)
                .animation(reduceMotion ? nil : .smooth(duration: 0.35), value: progress)
            // On completion the dots fuse into one glowing line.
            HaloCapsule()
                .stroke(tint, lineWidth: 2.5)
                .shadow(color: tint.opacity(0.9), radius: pulse ? 10 : 3)
                .opacity(state.phase.isSending ? 0 : 1)
                .scaleEffect(pulse ? 1.04 : 1)
        }
        .frame(width: ring.width, height: ring.height)
        .overlay(alignment: .bottom) {
            // The ✓ lives in the pill under the island: to the side it would collide with
            // the status-bar icons (signal, battery) that sit right next to the island.
            HStack(spacing: 6) {
                if !state.phase.isSending {
                    Badge(phase: state.phase, tint: tint, draw: drawCheck)
                        .transition(.scale(scale: 0.3).combined(with: .opacity))
                }
                Text(state.label)
                    .font(.system(.footnote, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                    .contentTransition(.numericText(value: progress))
            }
            .foregroundStyle(.primary.opacity(0.85))
            .padding(.leading, state.phase.isSending ? 10 : 4).padding(.trailing, 10).padding(.vertical, 4)
            .background(.ultraThinMaterial, in: Capsule())
            .offset(y: 32)
            .animation(.spring(response: 0.4, dampingFraction: 0.7), value: state.phase.isSending)
            .animation(.snappy, value: state.label)
        }
        .position(x: ring.midX, y: ring.midY)
        .onChange(of: state.phase) { _, phase in finish(phase) }
        .onAppear { finish(state.phase) }
    }

    private var dots: StrokeStyle {
        // Near-zero dashes with round caps render as evenly spaced dots.
        StrokeStyle(lineWidth: 3.6, lineCap: .round, dash: [0.001, 7.5])
    }

    private func finish(_ phase: HaloState.Phase) {
        guard !phase.isSending else { return }
        let feedback = UINotificationFeedbackGenerator()
        feedback.notificationOccurred(phase == .done ? .success : .error)
        withAnimation(.spring(response: 0.35, dampingFraction: 0.55)) { pulse = true }
        withAnimation(.easeOut(duration: 0.35).delay(0.12)) { drawCheck = true }
        withAnimation(.spring(response: 0.5, dampingFraction: 0.8).delay(0.35)) { pulse = false }
    }
}

/// Capsule hugging the island, with the path starting at the bottom centre and running
/// clockwise, so progress grows from the "chin" round both shoulders like a fuse.
private struct HaloCapsule: Shape {
    func path(in rect: CGRect) -> Path {
        let r = rect.height / 2
        var p = Path()
        p.move(to: CGPoint(x: rect.midX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        p.addArc(center: CGPoint(x: rect.minX + r, y: rect.midY), radius: r, startAngle: .degrees(90), endAngle: .degrees(270), clockwise: false)
        p.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        p.addArc(center: CGPoint(x: rect.maxX - r, y: rect.midY), radius: r, startAngle: .degrees(270), endAngle: .degrees(90), clockwise: false)
        p.closeSubpath()
        return p
    }
}

private struct Badge: View {
    let phase: HaloState.Phase
    let tint: Color
    let draw: Bool

    var body: some View {
        ZStack {
            Circle().fill(tint)
                .shadow(color: tint.opacity(0.6), radius: 8)
            if phase == .done {
                CheckmarkShape()
                    .trim(from: 0, to: draw ? 1 : 0)
                    .stroke(.white, style: StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round))
                    .padding(5)
            } else {
                Image(systemName: "xmark").font(.system(size: 9, weight: .heavy)).foregroundStyle(.white)
            }
        }
        .frame(width: 20, height: 20)
    }
}

private struct CheckmarkShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX + rect.width * 0.08, y: rect.midY + rect.height * 0.04))
        p.addLine(to: CGPoint(x: rect.minX + rect.width * 0.38, y: rect.maxY - rect.height * 0.12))
        p.addLine(to: CGPoint(x: rect.maxX - rect.width * 0.06, y: rect.minY + rect.height * 0.14))
        return p
    }
}
