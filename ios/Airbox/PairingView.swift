import SwiftUI
import VisionKit

/// One-time pairing: scan the QR code the Android app shows (or paste its link).
struct PairingView: View {
    @EnvironmentObject private var model: AppModel
    @State private var link = ""
    @State private var error: String?
    @State private var busy = false
    @State private var scanning = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Image(systemName: "iphone.and.arrow.forward").font(.system(size: 56)).foregroundStyle(.tint)
                Text("Connect your Android phone").font(.title2.bold())
                Text("On Android, open Airbox and tap **Connect iPhone**. Both phones must be on the same Wi-Fi (or this iPhone on the Android hotspot).")
                    .multilineTextAlignment(.center).foregroundStyle(.secondary)

                if DataScannerViewController.isSupported {
                    Button { scanning = true } label: {
                        Label("Scan QR code", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                }

                TextField("…or paste the link", text: $link)
                    .textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Connect") { pair(link) }.disabled(link.isEmpty || busy)

                if busy { ProgressView() }
                if let error { Text(error).foregroundStyle(.red).font(.footnote).multilineTextAlignment(.center) }
                Spacer()
            }
            .padding(24)
            .sheet(isPresented: $scanning) {
                QRScanner { code in
                    scanning = false
                    pair(code)
                }
                .ignoresSafeArea()
            }
        }
    }

    private func pair(_ value: String) {
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let config = try await AirboxClient.shared.pair(link: value.trimmingCharacters(in: .whitespacesAndNewlines),
                                                                phoneName: UIDevice.current.name)
                model.paired(config)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

private struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])],
                                                qualityLevel: .balanced, isHighlightingEnabled: true)
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onCode) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        private var done = false
        init(_ onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            for case .barcode(let code) in items {
                guard !done, let value = code.payloadStringValue, value.hasPrefix("http://") else { continue }
                done = true
                scanner.stopScanning()
                onCode(value)
            }
        }
    }
}
