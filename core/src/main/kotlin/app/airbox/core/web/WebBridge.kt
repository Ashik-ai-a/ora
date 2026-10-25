package app.airbox.core.web

import app.airbox.core.protocol.FileEntry
import app.airbox.core.security.constantTimeEquals
import app.airbox.core.security.randomBytes
import app.airbox.core.security.toBase64Url
import app.airbox.core.transfer.FileNames
import app.airbox.core.transfer.OutgoingFile
import app.airbox.core.transfer.ReceiveStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import app.airbox.core.live.IslandAction
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets an iPhone with NO app installed send and receive through the Airbox Android app:
 *
 *  - Safari: the iPhone camera scans a QR code on the Android screen and opens a page
 *    served by the phone. Paste text, pick photos/files, or copy what Android shared.
 *  - Shortcuts (built into iOS): "Get Clipboard → Get Contents of URL" posts the iPhone
 *    clipboard here, triggered by Back Tap or the share sheet. Uses a revocable device key.
 *
 * This is plain HTTP on the local network (Safari can't trust a phone's self-signed
 * certificate without scary warnings). Access needs a secret, but traffic is only as
 * private as the Wi-Fi it runs on. Use the Android hotspot mode, or a home network,
 * not public Wi-Fi. See docs/NO_APP_IPHONE.md.
 */
class WebBridge(
    private val storage: ReceiveStorage,
    private val deviceName: String,
    /** Text sent from the iPhone. Android puts it on the clipboard and shows a notification. */
    private val onText: (String) -> Unit,
    /** A file was saved (final name). */
    private val onFile: (FileEntry, String) -> Unit = { _, _ -> },
    /** A button pressed on the iPhone (Dynamic Island / app): e.g. STOP_RING, DECLINE_CALL. */
    private val onAction: (IslandAction, String?) -> Unit = { _, _ -> },
    port: Int = 0,
    bindAddress: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxUploadBytes: Long = 8L * 1024 * 1024 * 1024,
    private val storageReserveBytes: Long = 256L * 1024 * 1024,
) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(if (bindAddress != null) InetSocketAddress(bindAddress, port) else InetSocketAddress(port))
    }
    val port: Int get() = server.localPort
    private val running = AtomicBoolean(true)
    private val pool = Executors.newFixedThreadPool(16) { r -> Thread(r, "airbox-web").apply { isDaemon = true } }
    private val json = Json { encodeDefaults = true }

    private class Expiring(val expiresAt: Long)
    private val visitTokens = ConcurrentHashMap<String, Expiring>()
    private val sessions = ConcurrentHashMap<String, Expiring>()
    private val deviceKeys = ConcurrentHashMap<String, String>() // key -> label
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

    @Serializable data class SharedText(val text: String, val updatedAtEpochMs: Long)
    @Serializable data class SharedFileInfo(val id: String, val name: String, val size: Long, val mimeType: String)
    private class Shared(val info: SharedFileInfo, val file: OutgoingFile)

    @Volatile private var sharedText: SharedText? = null

    @Serializable data class Event(val seq: Long, val type: String, val data: JsonElement)
    @Serializable data class EventPage(val events: List<Event>, val next: Long)
    @Serializable data class ActionRequest(val action: IslandAction, val target: String? = null)
    @Serializable data class AppPairing(val key: String, val deviceName: String)

    private val events = ArrayDeque<Event>()
    private val eventLock = Object()
    private var nextSeq = 1L
    private val sharedFiles = ConcurrentHashMap<String, Shared>()

    fun start(): WebBridge {
        Thread({ acceptLoop() }, "airbox-web-accept").apply { isDaemon = true }.start()
        return this
    }

    /** URL for the QR code. Single use, valid [ttlMillis]. */
    fun pairingUrl(host: String, ttlMillis: Long = 10 * 60_000): String {
        val token = randomBytes(16).toBase64Url()
        visitTokens[token] = Expiring(clock() + ttlMillis)
        return "http://${hostForUrl(host)}:$port/?t=$token"
    }

    /** Long-lived key for an iOS Shortcut. Show once; the user can revoke it. */
    fun createShortcutKey(label: String): String = randomBytes(24).toBase64Url().also { deviceKeys[it] = label }

    fun revokeShortcutKey(key: String) { deviceKeys.remove(key) }

    /** Signs every browser out and invalidates QR codes. */
    fun revokeAllSessions() { sessions.clear(); visitTokens.clear() }

    /** Text the iPhone can copy from the page (Android -> iPhone clipboard). */
    fun shareText(text: String) {
        sharedText = SharedText(text.take(MAX_TEXT), clock())
        publish("text", json.encodeToJsonElement(SharedText.serializer(), sharedText!!))
    }

    /**
     * Pushes a live event to connected iPhone apps (long-poll on /api/events): calls, ringing,
     * now playing, transfer progress. [data] is usually an encoded IslandItem.
     */
    fun publish(type: String, data: JsonElement) {
        synchronized(eventLock) {
            events.addLast(Event(nextSeq++, type, data))
            while (events.size > MAX_EVENTS) events.removeFirst()
            eventLock.notifyAll()
        }
    }

    /** Shows (or updates) an item on the iPhone's Dynamic Island via the Airbox iPhone app. */
    fun publishIsland(item: app.airbox.core.live.IslandItem) =
        publish("island", json.parseToJsonElement(app.airbox.core.live.IslandModel.encode(item)))

    fun endIsland(id: String) = publish("island_end", json.parseToJsonElement("""{"id":${json.encodeToString(id)}}"""))

    private fun eventsAfter(after: Long, waitMs: Long): EventPage {
        val deadline = clock() + waitMs
        synchronized(eventLock) {
            while (true) {
                val fresh = events.filter { it.seq > after }
                if (fresh.isNotEmpty()) return EventPage(fresh, fresh.last().seq)
                val left = deadline - clock()
                if (left <= 0 || !running.get()) return EventPage(emptyList(), maxOf(after, nextSeq - 1))
                eventLock.wait(minOf(left, 1_000))
            }
        }
    }

    fun shareFile(file: OutgoingFile): String {
        val id = randomBytes(9).toBase64Url()
        val info = SharedFileInfo(id, file.name, file.size, file.mimeType)
        sharedFiles[id] = Shared(info, file)
        publish("file", json.encodeToJsonElement(SharedFileInfo.serializer(), info))
        return id
    }

    fun unshareFile(id: String) { sharedFiles.remove(id) }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try { server.accept() } catch (e: SocketException) { break }
            pool.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val remote = (s.remoteSocketAddress as InetSocketAddress).address.hostAddress
            val response = try {
                val request = MiniHttp.parse(s.getInputStream(), remote)
                handle(request).also {
                    // Drain small leftovers so the client sees our response, not a reset.
                    if (!(request.body as LimitedInputStream).exhausted && request.contentLength < 64 * 1024) {
                        request.body.readAllBytes()
                    }
                }
            } catch (e: HttpError) {
                HttpResponse.text(e.status, e.message ?: "")
            } catch (e: Exception) {
                return
            }
            runCatching { MiniHttp.write(s.getOutputStream(), response) }
        }
    }

    internal fun handle(req: HttpRequest): HttpResponse {
        checkHost(req)
        if (lockedOut(req.remoteAddress)) return HttpResponse.text(429, "Too many attempts. Wait a few minutes.")

        // Static assets carry no secrets and are needed before sign-in.
        when (req.path) {
            "/app.js" -> return asset("app.js", "text/javascript; charset=utf-8")
            "/app.css" -> return asset("app.css", "text/css; charset=utf-8")
        }

        if (req.method == "GET" && req.path == "/" && req.query["t"] != null) return redeemVisit(req)
        if (req.method == "POST" && req.path == "/api/app-pair") return pairApp(req)

        val authed = sessionValid(req.cookies["airbox_sid"]) || bearerValid(req)
        if (!authed) {
            if (req.cookies["airbox_sid"] != null || req.header("authorization") != null || req.query["t"] != null) {
                recordFailure(req.remoteAddress)
            }
            return if (req.path == "/") page("signed-out.html") else HttpResponse.text(401, "Scan the QR code on the Android phone again.")
        }

        if (req.method == "POST") {
            // CSRF: a custom header forces a CORS preflight, which we never approve.
            // Shortcuts uses the Bearer key instead of a cookie, so it isn't exposed to CSRF.
            if (req.header("x-airbox") != "1" && req.header("authorization") == null) throw HttpError(403, "Missing header")
        }

        return when {
            req.method == "GET" && req.path == "/" -> page("index.html")
            req.method == "GET" && req.path == "/api/info" -> HttpResponse.json(json.encodeToString(mapOf("deviceName" to deviceName)))
            req.method == "POST" && req.path == "/api/text" -> receiveText(req)
            req.method == "GET" && req.path == "/api/text" -> HttpResponse.json(json.encodeToString(sharedText))
            req.method == "POST" && req.path == "/api/upload" -> receiveFile(req)
            req.method == "GET" && req.path == "/api/files" -> HttpResponse.json(json.encodeToString(sharedFiles.values.map { it.info }))
            req.method == "GET" && req.path.startsWith("/api/files/") -> download(req.path.removePrefix("/api/files/"))
            req.method == "GET" && req.path == "/api/events" -> {
                val after = req.query["after"]?.toLongOrNull() ?: 0
                val wait = (req.query["wait"]?.toLongOrNull() ?: 25).coerceIn(0, 25) * 1000
                HttpResponse.json(json.encodeToString(eventsAfter(after, wait)))
            }
            req.method == "POST" && req.path == "/api/action" -> {
                val request = runCatching { json.decodeFromString<ActionRequest>(req.readBodyText(4_096)) }
                    .getOrElse { throw HttpError(400, "Bad action") }
                onAction(request.action, request.target)
                HttpResponse.empty()
            }
            else -> HttpResponse.text(404, "Not found")
        }
    }

    private fun redeemVisit(req: HttpRequest): HttpResponse {
        val token = req.query.getValue("t")
        val match = visitTokens.keys.firstOrNull { constantTimeEquals(it, token) }
        val entry = match?.let { visitTokens.remove(it) }
        if (entry == null || entry.expiresAt <= clock()) {
            recordFailure(req.remoteAddress)
            return page("signed-out.html", 401)
        }
        val sid = randomBytes(24).toBase64Url()
        sessions[sid] = Expiring(clock() + SESSION_MS)
        // Redirect drops the token from the address bar and history.
        return HttpResponse.redirect("/").apply {
            headers["Set-Cookie"] = "airbox_sid=$sid; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_MS / 1000}"
        }
    }

    /**
     * The Airbox iPhone app scanned the QR code: trade the single-use token for a long-lived,
     * revocable key (the same kind a Shortcut uses).
     */
    private fun pairApp(req: HttpRequest): HttpResponse {
        if (req.header("x-airbox") != "1") throw HttpError(403, "Missing header")
        val token = req.query["t"] ?: throw HttpError(400, "Missing token")
        val match = visitTokens.keys.firstOrNull { constantTimeEquals(it, token) }
        val entry = match?.let { visitTokens.remove(it) }
        if (entry == null || entry.expiresAt <= clock()) {
            recordFailure(req.remoteAddress)
            return HttpResponse.text(401, "This code has expired. Show a new one on the Android phone.")
        }
        val name = req.query["name"]?.take(60)?.ifBlank { null } ?: "iPhone app"
        return HttpResponse.json(json.encodeToString(AppPairing(createShortcutKey(name), deviceName)))
    }

    private fun sessionValid(sid: String?): Boolean {
        if (sid == null) return false
        val e = sessions[sid] ?: return false
        if (e.expiresAt <= clock()) { sessions.remove(sid); return false }
        return true
    }

    private fun bearerValid(req: HttpRequest): Boolean {
        val key = req.header("authorization")?.removePrefix("Bearer ")?.trim() ?: return false
        return deviceKeys.keys.any { constantTimeEquals(it, key) }
    }

    private fun receiveText(req: HttpRequest): HttpResponse {
        val text = req.readBodyText(MAX_TEXT).trimEnd('\n')
        if (text.isEmpty()) return HttpResponse.text(400, "Nothing to send")
        onText(text)
        return HttpResponse.empty()
    }

    private fun receiveFile(req: HttpRequest): HttpResponse {
        val rawName = req.query["name"]?.takeIf { it.isNotBlank() } ?: throw HttpError(400, "Missing name")
        val size = req.contentLength
        if (size > maxUploadBytes) throw HttpError(413, "File too large")
        if (storage.freeBytes() - storageReserveBytes < size) throw HttpError(507, "Not enough space on the Android phone")
        val entry = FileEntry(0, rawName, size, req.header("content-type") ?: "application/octet-stream", null)
        val transferId = "web-" + randomBytes(9).toBase64Url()
        val partial = storage.partial(transferId, entry)
        try {
            partial.openAppend().use { out -> req.body.copyTo(out, 64 * 1024) }
            // Browser uploads carry no checksum over HTTP; at least never publish a truncated file.
            if (partial.length != size) throw HttpError(400, "Upload interrupted")
            val saved = storage.publish(partial, FileNames.sanitize(rawName), entry)
            onFile(entry, saved)
            return HttpResponse.json(json.encodeToString(mapOf("savedAs" to saved)))
        } finally {
            storage.discard(transferId)
        }
    }

    private fun download(id: String): HttpResponse {
        val shared = sharedFiles[id] ?: return HttpResponse.text(404, "No longer shared")
        val name = shared.info.name
        val ascii = FileNames.sanitize(name).replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
        val encoded = URLEncoder.encode(FileNames.sanitize(name), Charsets.UTF_8).replace("+", "%20")
        return HttpResponse(
            200,
            linkedMapOf(
                "Content-Type" to shared.info.mimeType,
                "Content-Disposition" to "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded",
            ),
            stream = { shared.file.open() },
            streamLength = shared.file.size,
        )
    }

    /** Blocks DNS-rebinding: only IP literals or .local names, never a public hostname. */
    private fun checkHost(req: HttpRequest) {
        val host = req.header("host")?.substringBeforeLast(':')?.trim('[', ']') ?: throw HttpError(400, "Missing host")
        val ok = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host.contains(':') || host.endsWith(".local") || host == "localhost"
        if (!ok) throw HttpError(403, "Bad host")
    }

    private fun recordFailure(address: String) {
        val list = failures.computeIfAbsent(address) { mutableListOf() }
        synchronized(list) { list += clock() }
    }

    private fun lockedOut(address: String): Boolean {
        val list = failures[address] ?: return false
        synchronized(list) {
            list.removeAll { clock() - it > LOCKOUT_WINDOW_MS }
            return list.size >= MAX_FAILURES
        }
    }

    private fun page(name: String, status: Int = 200): HttpResponse =
        asset(name, "text/html; charset=utf-8", status).apply {
            headers["Content-Security-Policy"] =
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action 'self'"
        }

    private fun asset(name: String, type: String, status: Int = 200): HttpResponse {
        val bytes = WebBridge::class.java.getResourceAsStream("/airbox-web/$name")?.use { it.readBytes() }
            ?: return HttpResponse.text(404, "Not found")
        return HttpResponse(status, linkedMapOf("Content-Type" to type), bytes)
    }

    private fun hostForUrl(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host

    override fun close() {
        running.set(false)
        synchronized(eventLock) { eventLock.notifyAll() }
        runCatching { server.close() }
        pool.shutdownNow()
    }

    companion object {
        const val MAX_TEXT = 256 * 1024
        const val MAX_EVENTS = 200
        const val SESSION_MS = 12 * 60 * 60_000L
        const val MAX_FAILURES = 10
        const val LOCKOUT_WINDOW_MS = 5 * 60_000L
    }
}

/** QR payload the iPhone camera understands for joining the Android hotspot. */
object WifiQr {
    fun encode(ssid: String, password: String, hidden: Boolean = false): String {
        fun esc(s: String) = s.replace(Regex("""([\;,:"])"""), """\\$1""")
        return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};${if (hidden) "H:true;" else ""};"
    }
}
