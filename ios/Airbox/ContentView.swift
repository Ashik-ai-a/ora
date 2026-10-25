import PhotosUI
import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @State private var typed = ""
    @State private var photos: [PhotosPickerItem] = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    connection

                    // The main action: one tap, no "Allow Paste?" prompt.
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Copy anything on this iPhone, then:").font(.subheadline).foregroundStyle(.secondary)
                        PasteButton(payloadType: String.self) { strings in
                            model.send(pasted: strings)
                        }
                        .buttonBorderShape(.capsule)
                        .controlSize(.large)
                        .tint(.accentColor)
                        .labelStyle(.titleAndIcon)
                        .frame(maxWidth: .infinity)
                        Text("Sends to \(model.peerName) and lands on its clipboard.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    .card()

                    VStack(alignment: .leading, spacing: 12) {
                        TextField("Or type a message", text: $typed, axis: .vertical).lineLimit(1...5)
                        Button("Send text") { model.send(text: typed); typed = "" }
                            .buttonStyle(.bordered).disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    .card()

                    VStack(alignment: .leading, spacing: 12) {
                        PhotosPicker(selection: $photos, matching: .any(of: [.images, .videos])) {
                            Label("Send photos & videos", systemImage: "photo.on.rectangle").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                        ForEach(model.uploads) { upload in
                            HStack {
                                Text(upload.name).lineLimit(1)
                                Spacer()
                                if upload.failed { Text("Failed").foregroundStyle(.red) }
                                else if upload.done { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                                else { ProgressView(value: upload.progress).frame(width: 80) }
                            }.font(.subheadline)
                        }
                    }
                    .card()

                    if let incoming = model.incomingText {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("From \(model.peerName)").font(.headline)
                            Text(incoming.text).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                            Button { model.copyIncoming() } label: { Label("Copy", systemImage: "doc.on.doc") }
                                .buttonStyle(.bordered)
                        }
                        .card()
                    }

                    if let status = model.status {
                        Text(status).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Airbox")
            .toolbar {
                Menu {
                    Button("Unpair \(model.peerName)", role: .destructive) { model.unpair() }
                } label: { Image(systemName: "ellipsis.circle") }
            }
            .onChange(of: photos) { _, items in
                guard !items.isEmpty else { return }
                Task { await sendPhotos(items) }
            }
        }
    }

    private var connection: some View {
        HStack(spacing: 8) {
            Circle().fill(model.reachable ? .green : .orange).frame(width: 9, height: 9)
            Text(model.reachable ? "Connected to \(model.peerName)" : "Looking for \(model.peerName) on this Wi-Fi…")
                .font(.subheadline).foregroundStyle(.secondary)
            Spacer()
        }
    }

    private func sendPhotos(_ items: [PhotosPickerItem]) async {
        var files: [(Data, String, String)] = []
        for (i, item) in items.enumerated() {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let type = item.supportedContentTypes.first
            let ext = type?.preferredFilenameExtension ?? "jpg"
            let stamp = Date().formatted(.iso8601.year().month().day().time(includingFractionalSeconds: false)).replacingOccurrences(of: ":", with: "")
            files.append((data, "IMG_\(stamp)_\(i + 1).\(ext)", type?.preferredMIMEType ?? "application/octet-stream"))
        }
        photos = []
        if !files.isEmpty { model.upload(files) }
    }
}

private extension View {
    func card() -> some View {
        padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
