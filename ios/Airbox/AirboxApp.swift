import SwiftUI

@main
struct AirboxApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                if model.peer == nil { PairingView() } else { ContentView() }
            }
            .environmentObject(model)
            .onChange(of: scenePhase) { _, phase in model.scenePhaseChanged(phase) }
        }
    }
}
