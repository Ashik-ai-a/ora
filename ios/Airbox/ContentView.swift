import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @State private var typed = ""
    @State private var photos: [PhotosPickerItem] = []
    @State private var importingFiles = false
    @FocusState private var composing: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    PeerRadar(name: model.peerName, reachable: model.reachable, halo: model.halo)

                    composer

                    actions

                    if !model.uploads.isEmpty { sentList }

                    inbox

                    Text("Sent straight over your Wi-Fi. Nothing goes through the internet.")
                        .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Airbox")
            .toolbar {
                Menu {
                    Button("Unpair \(model.peerName)", systemImage: "link.badge.minus", role: .destructive) { model.unpair() }
                } label: { Image(systemName: "ellipsis.circle") }
            }
            .onChange(of: photos) { _, items in
                guard !items.isEmpty else { return }
                Task { await sendPhotos(items) }
            }
            .fileImporter(isPresented: $importingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                if case .success(let urls) = result { sendFiles(urls) }
            }
        }
    }

    // MARK: Compose

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField("Type something to send", text: $typed, axis: .vertical)
                .lineLimit(1...6)
                .focused($composing)
                .padding(.vertical, 11)
            Button {
                model.send(text: typed)
                typed = ""
                composing = false
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 15, weight: .bold))
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(canSend ? Color.accentColor : Color(.systemFill)))
                    .foregroundStyle(.white)
            }
            .disabled(!canSend)
            .padding(.bottom, 6)
            .accessibilityLabel("Send")
        }
        .padding(.leading, 16).padding(.trailing, 6)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .animation(.snappy, value: canSend)
    }

    private var canSend: Bool { !typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    // MARK: Actions row (share-sheet style)

    private var actions: some View {
        HStack(alignment: .top) {
            ActionSlot(title: "Paste") {
                // Apple's own Paste button: no "Allow Paste?" alert.
                PasteButton(payloadType: String.self) { strings in model.send(pasted: strings) }
                    .labelStyle(.iconOnly)
                    .buttonBorderShape(.circle)
                    .controlSize(.extraLarge)
                    .tint(Color(.secondarySystemGroupedBackground))
                    .foregroundStyle(Color.accentColor)
            }
            ActionSlot(title: "Photos") {
                PhotosPicker(selection: $photos, matching: .any(of: [.images, .videos, .livePhotos])) {
                    RoundIcon(systemName: "photo.on.rectangle.angled")
                }
            }
            ActionSlot(title: "Files") {
                Button { importingFiles = true } label: { RoundIcon(systemName: "doc") }
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: Lists

    private var sentList: some View {
        VStack(spacing: 0) {
            ForEach(model.uploads) { upload in
                HStack(spacing: 12) {
                    MiniRing(progress: upload.progress, done: upload.done, failed: upload.failed)
                    Text(upload.name).lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Text(upload.failed ? "Failed" : upload.done ? "Sent" : "\(Int(upload.progress * 100))%")
                        .font(.subheadline.weight(upload.done ? .semibold : .regular))
                        .monospacedDigit()
                        .contentTransition(.numericText(value: upload.progress))
                        .foregroundStyle(upload.failed ? AnyShapeStyle(Color.red) : upload.done ? AnyShapeStyle(Color.green) : AnyShapeStyle(HierarchicalShapeStyle.secondary))
                }
                .frame(minHeight: 50)
                if upload.id != model.uploads.last?.id { Divider() }
            }
        }
        .padding(.horizontal, 16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .animation(.snappy, value: model.uploads.map(\.progress))
    }

    private var inbox: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("From \(model.peerName)").font(.title3.weight(.semibold)).padding(.horizontal, 4)
            Group {
                if let incoming = model.incomingText {
                    HStack(alignment: .top, spacing: 12) {
                        Text(incoming.text).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                        Button(model.justCopied ? "Copied" : "Copy") { model.copyIncoming() }
                            .buttonStyle(.bordered).buttonBorderShape(.capsule).controlSize(.small)
                    }
                    .padding(.vertical, 14)
                } else {
                    Text("Share something to Airbox on \(model.peerName) and pick this iPhone. It appears here.")
                        .font(.subheadline).foregroundStyle(.secondary).padding(.vertical, 16)
                }
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }

    // MARK: Sending files

    private func sendPhotos(_ items: [PhotosPickerItem]) async {
        var files: [(Data, String, String)] = []
        for (i, item) in items.enumerated() {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let type = item.supportedContentTypes.first
            let ext = type?.preferredFilenameExtension ?? "jpg"
            let stamp = Date().formatted(.iso8601.year().month().day().time(includingFractionalSeconds: false))
                .replacingOccurrences(of: ":", with: "")
            files.append((data, "IMG_\(stamp)_\(i + 1).\(ext)", type?.preferredMIMEType ?? "application/octet-stream"))
        }
        photos = []
        if !files.isEmpty { model.upload(files) }
    }

    private func sendFiles(_ urls: [URL]) {
        var files: [(Data, String, String)] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { continue }
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            files.append((data, url.lastPathComponent, mime))
        }
        if !files.isEmpty { model.upload(files) }
    }
}

private struct ActionSlot<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 8) {
            content.frame(width: 62, height: 62)
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct RoundIcon: View {
    let systemName: String

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 24, weight: .regular))
            .foregroundStyle(Color.accentColor)
            .frame(width: 62, height: 62)
            .background(Circle().fill(Color(.secondarySystemGroupedBackground)))
            .contentShape(Circle())
    }
}

private struct MiniRing: View {
    let progress: Double
    let done: Bool
    let failed: Bool

    var body: some View {
        ZStack {
            Circle().stroke(Color(.systemFill), lineWidth: 3)
            Circle().trim(from: 0, to: done ? 1 : progress)
                .stroke(failed ? Color.red : done ? Color.green : Color.accentColor, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 22, height: 22)
        .animation(.smooth(duration: 0.25), value: progress)
    }
}
