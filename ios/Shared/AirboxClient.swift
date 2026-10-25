import Foundation

enum AirboxError: LocalizedError {
    case notPaired, badLink, http(Int, String)

    var errorDescription: String? {
        switch self {
        case .notPaired: "Pair with your Android phone first."
        case .badLink: "That isn't an Airbox code. Open Airbox on Android and tap Connect iPhone."
        case .http(401, _): "The Android phone no longer recognises this iPhone. Pair again."
        case .http(_, let message): message.isEmpty ? "The Android phone couldn't be reached." : message
        }
    }
}

/// Talks to the Airbox Android app over the local network (see core/.../web/WebBridge.kt).
final class AirboxClient: @unchecked Sendable {
    static let shared = AirboxClient()

    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 35 // long-poll waits up to 25 s
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    var config: PeerConfig? { Keychain.load() }

    /// Called with the URL from the QR code shown on Android (`http://ip:port/?t=…`).
    func pair(link: String, phoneName: String) async throws -> PeerConfig {
        guard let comps = URLComponents(string: link), comps.scheme == "http",
              let host = comps.host, let port = comps.port,
              let token = comps.queryItems?.first(where: { $0.name == "t" })?.value else { throw AirboxError.badLink }
        var base = URLComponents()
        base.scheme = "http"; base.host = host; base.port = port
        guard let baseURL = base.url else { throw AirboxError.badLink }
        var pair = URLComponents(url: baseURL.appending(path: "api/app-pair"), resolvingAgainstBaseURL: false)!
        pair.queryItems = [URLQueryItem(name: "t", value: token), URLQueryItem(name: "name", value: phoneName)]
        var request = URLRequest(url: pair.url!)
        request.httpMethod = "POST"
        request.setValue("1", forHTTPHeaderField: "X-Airbox")
        let (data, response) = try await session.data(for: request)
        try check(response, data)
        struct Reply: Decodable { let key: String; let deviceName: String }
        let reply = try JSONDecoder().decode(Reply.self, from: data)
        let config = PeerConfig(baseURL: baseURL, key: reply.key, deviceName: reply.deviceName)
        try Keychain.save(config)
        return config
    }

    func sendText(_ text: String) async throws {
        var request = try authorized("api/text", method: "POST")
        request.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await session.upload(for: request, from: Data(text.utf8))
        try check(response, data)
    }

    /// Uploads one file and reports progress (0…1) as it goes.
    func upload(_ data: Data, name: String, mimeType: String, progress: @escaping @Sendable (Double) -> Void) async throws {
        var request = try authorized("api/upload", method: "POST", query: [URLQueryItem(name: "name", value: name)])
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        let delegate = UploadProgress(progress)
        let (body, response) = try await session.upload(for: request, from: data, delegate: delegate)
        try check(response, body)
    }

    struct SharedText: Decodable { let text: String; let updatedAtEpochMs: Int64 }

    func sharedText() async throws -> SharedText? {
        let (data, response) = try await session.data(for: try authorized("api/text", method: "GET"))
        try check(response, data)
        return try JSONDecoder().decode(SharedText?.self, from: data)
    }

    struct Event: Decodable {
        let seq: Int64
        let type: String
        let data: AnyJSON
    }
    struct EventPage: Decodable { let events: [Event]; let next: Int64 }

    /// Long-poll: returns as soon as Android publishes something, or after ~25 s.
    func events(after: Int64) async throws -> EventPage {
        let request = try authorized("api/events", method: "GET",
                                     query: [URLQueryItem(name: "after", value: String(after)), URLQueryItem(name: "wait", value: "25")])
        let (data, response) = try await session.data(for: request)
        try check(response, data)
        return try JSONDecoder().decode(EventPage.self, from: data)
    }

    /// A Dynamic Island / app button: stop ringing, decline call, media control, cancel.
    func sendAction(_ action: IslandItem.Action, target: String?) async throws {
        var request = try authorized("api/action", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        struct Body: Encodable { let action: String; let target: String? }
        let body = try JSONEncoder().encode(Body(action: action.rawValue, target: target))
        let (data, response) = try await session.upload(for: request, from: body)
        try check(response, data)
    }

    private func authorized(_ path: String, method: String, query: [URLQueryItem] = []) throws -> URLRequest {
        guard let config else { throw AirboxError.notPaired }
        var comps = URLComponents(url: config.baseURL.appending(path: path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { comps.queryItems = query }
        var request = URLRequest(url: comps.url!)
        request.httpMethod = method
        request.setValue("Bearer \(config.key)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func check(_ response: URLResponse, _ data: Data) throws {
        guard let http = response as? HTTPURLResponse else { throw AirboxError.http(0, "") }
        guard (200..<300).contains(http.statusCode) else {
            throw AirboxError.http(http.statusCode, String(decoding: data.prefix(300), as: UTF8.self))
        }
    }
}

private final class UploadProgress: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    let report: @Sendable (Double) -> Void
    init(_ report: @escaping @Sendable (Double) -> Void) { self.report = report }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard totalBytesExpectedToSend > 0 else { return }
        report(Double(totalBytesSent) / Double(totalBytesExpectedToSend))
    }
}

/// Keeps an event's `data` as raw JSON so each event type can decode its own shape.
struct AnyJSON: Decodable {
    let raw: Data
    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(JSONValue.self)
        raw = try JSONEncoder().encode(value)
    }
    func decode<T: Decodable>(_ type: T.Type) throws -> T { try JSONDecoder().decode(type, from: raw) }
}

indirect enum JSONValue: Codable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        case .object(let o): try c.encode(o)
        case .array(let a): try c.encode(a)
        case .null: try c.encodeNil()
        }
    }
}
