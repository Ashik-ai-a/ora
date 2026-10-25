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
    /** A file was saved (final name), after it was accepted. */
    private val onFile: (FileEntry, String) -> Unit = { _, _ -> },
    /** A file arrived and waits for Accept / Decline. Show the prompt, then call [accept] or [decline]. */
    private val onIncoming: (PendingTransfer) -> Unit = { },
    /** An image copied on the iPhone (saved as [String]); put it on the Android clipboard. */
    private val onClipboardImage: (FileEntry, String) -> Unit = { _, _ -> },
    /** Paired phones survive restarts when this is a file store. */
    private val pairedStore: PairedDeviceStore = InMemoryPairedDeviceStore(),
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
    private val paired = ConcurrentHashMap<String, PairedDevice>().apply { pairedStore.load().forEach { put(it.keyHash, it) } }
    private val pending = ConcurrentHashMap<String, PendingEntry>()
    private val finished = ConcurrentHashMap<String, String>() // transfer id -> final status
    private val acceptedBatches = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

    @Serializable data class SharedText(val text: String, val updatedAtEpochMs: Long)
    @Serializable data class SharedFileInfo(val id: String, val name: String, val size: Long, val mimeType: String, val sharedAtEpochMs: Long = 0)
    private class Shared(val info: SharedFileInfo, val file: OutgoingFile)

    @Volatile private var sharedText: SharedText? = null

    @Serializable data class Event(val seq: Long, val type: String, val data: JsonElement)
    @Serializable data class EventPage(val events: List<Event>, val next: Long)
    @Serializable data class ActionRequest(val action: IslandAction, val target: String? = null)
    @Serializable data class AppPairing(val key: String, val deviceName: String)
    @Serializable data class TransferStatus(val id: String, val status: String, val savedAs: String? = null)

    /** What the Accept / Decline prompt shows. */
    data class PendingTransfer(
        val id: String,
        val fromDeviceId: String,
        val fromName: String,
        val fileName: String,
        val size: Long,
        val mimeType: String,
        /** Uploads from one share (several photos) share a batch: one prompt for all of them. */
        val batchId: String?,
    )

    private class PendingEntry(
        val transfer: PendingTransfer,
        val entry: FileEntry,
        val partial: app.airbox.core.transfer.PartialFile,
        val storageId: String,
        val expiresAt: Long,
    )

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

    /**
     * Pairs a phone and returns its long-lived key (shown once: for a Shortcut, the iPhone
     * app, or set as a browser cookie). The phone is remembered until [forget].
     */
    fun createShortcutKey(label: String): String {
        val key = randomBytes(24).toBase64Url()
        val hash = PairedDevice.hash(key)
        paired[hash] = PairedDevice(hash.take(12), hash, label.take(60), pairedAtEpochMs = clock())
        persist()
        return key
    }

    fun revokeShortcutKey(key: String) { paired.remove(PairedDevice.hash(key)); persist() }

    fun pairedDevices(): List<PairedDevice> = paired.values.sortedByDescending { it.lastSeenEpochMs }

    fun forget(deviceId: String) { paired.values.removeIf { it.id == deviceId }; persist() }

    fun rename(deviceId: String, name: String) = updateDevice(deviceId) { it.copy(name = name.take(60)) }

    fun setAutoAccept(deviceId: String, enabled: Boolean) = updateDevice(deviceId) { it.copy(autoAccept = enabled) }

    /** Forgets every paired phone and invalidates QR codes. */
    fun revokeAllSessions() { paired.clear(); visitTokens.clear(); persist() }

    private fun updateDevice(deviceId: String, change: (PairedDevice) -> PairedDevice) {
        val current = paired.values.firstOrNull { it.id == deviceId } ?: return
        paired[current.keyHash] = change(current)
        persist()
    }

    private fun persist() = pairedStore.save(paired.values.toList())

    /** Saves a waiting file. [always] also skips the prompt for this phone from now on. */
    fun accept(transferId: String, always: Boolean = false) {
        val first = pending[transferId] ?: return
        first.transfer.batchId?.let { acceptedBatches += it }
        if (always) setAutoAccept(first.transfer.fromDeviceId, true)
        val batch = first.transfer.batchId
        pending.values.filter { it.transfer.id == transferId || (batch != null && it.transfer.batchId == batch) }
            .forEach { save(it) }
    }

    /** Throws a waiting file (and the rest of its batch) away. */
    fun decline(transferId: String) {
        val first = pending[transferId] ?: return
        val batch = first.transfer.batchId
        pending.values.filter { it.transfer.id == transferId || (batch != null && it.transfer.batchId == batch) }
            .forEach { drop(it, "declined") }
    }

    fun pendingTransfers(): List<PendingTransfer> { expirePending(); return pending.values.map { it.transfer } }

    private fun save(p: PendingEntry) {
        if (pending.remove(p.transfer.id) == null) return
        try {
            val saved = storage.publish(p.partial, FileNames.sanitize(p.entry.name), p.entry)
            finished[p.transfer.id] = "saved:$saved"
            onFile(p.entry, saved)
        } finally {
            storage.discard(p.storageId)
        }
    }

    private fun drop(p: PendingEntry, status: String) {
        if (pending.remove(p.transfer.id) == null) return
        finished[p.transfer.id] = status
        storage.discard(p.storageId)
    }

    private fun expirePending() {
        val now = clock()
        pending.values.filter { it.expiresAt <= now }.forEach { drop(it, "expired") }
    }

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
        val info = SharedFileInfo(id, file.name, file.size, file.mimeType, clock())
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
            "/favicon.svg" -> return asset("favicon.svg", "image/svg+xml")
            "/apple-touch-icon.png" -> return asset("apple-touch-icon.png", "image/png")
        }

        if (req.method == "GET" && req.path == "/" && req.query["t"] != null) return redeemVisit(req)
        if (req.method == "POST" && req.path == "/api/app-pair") return pairApp(req)

        val device = deviceFor(req)
        if (device == null) {
            if (req.cookies[COOKIE] != null || req.header("authorization") != null || req.query["t"] != null) {
                recordFailure(req.remoteAddress)
            }
            return if (req.path == "/") page("signed-out.html") else HttpResponse.text(401, "Scan the QR code on the Android phone again.")
        }

        if (req.method == "POST" || req.method == "DELETE") {
            // CSRF: a custom header forces a CORS preflight, which we never approve.
            // Shortcuts uses the Bearer key instead of a cookie, so it isn't exposed to CSRF.
            if (req.header("x-airbox") != "1" && req.header("authorization") == null) throw HttpError(403, "Missing header")
        }

        return when {
            req.method == "GET" && req.path == "/" -> page("index.html")
            req.method == "GET" && req.path == "/api/info" -> HttpResponse.json(json.encodeToString(mapOf("deviceName" to deviceName)))
            req.method == "POST" && req.path == "/api/text" -> receiveText(req)
            req.method == "POST" && req.path == "/api/clipboard" -> receiveClipboard(req)
            req.method == "POST" && req.path == "/api/shortcut-key" -> {
                val key = createShortcutKey("${device.name} · Shortcuts")
                HttpResponse.json(json.encodeToString(mapOf("key" to key, "deviceName" to deviceName,
                    "upload" to "/api/upload", "clipboard" to "/api/clipboard")))
            }
            req.method == "GET" && req.path.startsWith("/api/transfers/") -> transferStatus(req.path.removePrefix("/api/transfers/"))
            req.method == "GET" && req.path == "/api/text" -> HttpResponse.json(json.encodeToString(sharedText))
            req.method == "POST" && req.path == "/api/upload" -> receiveFile(req, device)
            req.method == "GET" && req.path == "/api/files" -> HttpResponse.json(json.encodeToString(sharedFiles.values.map { it.info }.sortedByDescending { it.sharedAtEpochMs }))
            req.method == "GET" && req.path.startsWith("/api/files/") -> download(req.path.removePrefix("/api/files/"))
            // Swipe-to-delete on the iPhone: stop offering an item (the file stays on Android).
            req.method == "DELETE" && req.path.startsWith("/api/files/") -> {
                if (sharedFiles.remove(req.path.removePrefix("/api/files/")) == null) HttpResponse.text(404, "Not found")
                else HttpResponse.empty()
            }
            req.method == "DELETE" && req.path == "/api/text" -> { sharedText = null; HttpResponse.empty() }
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
        // Scanning once pairs this iPhone for good: a long-lived device key in a cookie.
        val key = createShortcutKey(req.query["name"]?.ifBlank { null } ?: "iPhone")
        // Redirect drops the token from the address bar and history.
        return HttpResponse.redirect("/").apply {
            headers["Set-Cookie"] = "$COOKIE=$key; Path=/; HttpOnly; SameSite=Strict; Max-Age=$REMEMBER_SECONDS"
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

    /** The paired phone behind a request: browser cookie or Bearer key (Shortcuts, the app). */
    private fun deviceFor(req: HttpRequest): PairedDevice? {
        val key = req.header("authorization")?.removePrefix("Bearer ")?.trim() ?: req.cookies[COOKIE] ?: return null
        val hash = PairedDevice.hash(key)
        val match = paired.keys.firstOrNull { constantTimeEquals(it, hash) } ?: return null
        val device = paired[match] ?: return null
        if (clock() - device.lastSeenEpochMs > 60_000) {
            paired[match] = device.copy(lastSeenEpochMs = clock())
            persist()
        }
        return device
    }

    private fun transferStatus(id: String): HttpResponse {
        expirePending()
        val status = when {
            pending.containsKey(id) -> TransferStatus(id, "pending")
            else -> finished[id]?.let {
                if (it.startsWith("saved:")) TransferStatus(id, "saved", it.removePrefix("saved:")) else TransferStatus(id, it)
            } ?: return HttpResponse.text(404, "Unknown transfer")
        }
        return HttpResponse.json(json.encodeToString(status))
    }

    /**
     * The iPhone's clipboard (from the page, a Shortcut or Back Tap). Text goes straight to
     * the Android clipboard; an image is saved and put on the clipboard too, so it pastes
     * into any app through the keyboard. No prompt: it's your own copy, from a paired phone.
     */
    private fun receiveClipboard(req: HttpRequest): HttpResponse {
        val type = req.header("content-type")?.substringBefore(';')?.trim()?.lowercase() ?: "text/plain"
        if (!type.startsWith("image/")) return receiveText(req)
        if (req.contentLength > MAX_CLIPBOARD_IMAGE) throw HttpError(413, "Image too large for the clipboard")
        val ext = when (type) { "image/png" -> "png"; "image/heic" -> "heic"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
        val entry = FileEntry(0, "Clipboard.$ext", req.contentLength, type, clock())
        val storageId = "clip-" + randomBytes(9).toBase64Url()
        val partial = storage.partial(storageId, entry)
        try {
            partial.openAppend().use { out -> req.body.copyTo(out, 64 * 1024) }
            if (partial.length != req.contentLength) throw HttpError(400, "Upload interrupted")
            val saved = storage.publish(partial, entry.name, entry)
            onClipboardImage(entry, saved)
            return HttpResponse.json(json.encodeToString(mapOf("savedAs" to saved)))
        } finally {
            storage.discard(storageId)
        }
    }

    private fun receiveText(req: HttpRequest): HttpResponse {
        val text = req.readBodyText(MAX_TEXT).trimEnd('\n')
        if (text.isEmpty()) return HttpResponse.text(400, "Nothing to send")
        onText(text)
        return HttpResponse.empty()
    }

    /**
     * A photo or file from the iPhone. It lands in private storage first and is only saved
     * where other apps can see it after Accept (or straight away for "Always accept" phones).
     * Replies 202 {"status":"pending"} while waiting; poll /api/transfers/{id}.
     */
    private fun receiveFile(req: HttpRequest, device: PairedDevice): HttpResponse {
        val rawName = req.query["name"]?.takeIf { it.isNotBlank() } ?: throw HttpError(400, "Missing name")
        val size = req.contentLength
        if (size > maxUploadBytes) throw HttpError(413, "File too large")
        if (storage.freeBytes() - storageReserveBytes < size) throw HttpError(507, "Not enough space on the Android phone")
        val received = FileEntry(0, rawName, size, req.header("content-type")?.substringBefore(';')?.trim() ?: "application/octet-stream", null)
        val id = randomBytes(9).toBase64Url()
        val storageId = "web-$id"
        val partial = storage.partial(storageId, received)
        try {
            partial.openAppend().use { out -> req.body.copyTo(out, 64 * 1024) }
        } catch (e: Exception) {
            storage.discard(storageId)
            throw e
        }
        // Browser uploads carry no checksum over HTTP; at least never keep a truncated file.
        if (partial.length != size) { storage.discard(storageId); throw HttpError(400, "Upload interrupted") }
        // Shortcuts often sends "Photo" as application/octet-stream: look at the bytes so the
        // Gallery recognises photos and videos, and give the file its proper extension.
        val head = partial.openRead().use { it.readNBytes(16) }
        val mime = received.mimeType.takeUnless { it == "application/octet-stream" || it.isBlank() } ?: MediaSniffer.sniff(head) ?: received.mimeType
        val named = MediaSniffer.withExtension(rawName, mime)
        val entry = received.copy(name = named, mimeType = mime)
        val batch = req.header("x-airbox-batch")?.take(64)?.ifBlank { null }?.let { "${device.id}:$it" }
        val transfer = PendingTransfer(id, device.id, device.name, FileNames.sanitize(named), size, entry.mimeType, batch)
        val p = PendingEntry(transfer, entry, partial, storageId, clock() + PENDING_MS)
        pending[id] = p
        if (device.autoAccept || (batch != null && batch in acceptedBatches)) {
            save(p)
        } else {
            onIncoming(transfer)
            publish("incoming", json.parseToJsonElement(json.encodeToString(TransferStatus(id, "pending"))))
        }
        return transferStatus(id).let { if (pending.containsKey(id)) HttpResponse(202, it.headers, it.body) else it }
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
        const val COOKIE = "airbox_device"
        /** A paired browser stays paired for a year (or until forgotten on Android). */
        const val REMEMBER_SECONDS = 365L * 24 * 60 * 60
        const val PENDING_MS = 10 * 60_000L
        const val MAX_CLIPBOARD_IMAGE = 50L * 1024 * 1024
        const val MAX_FAILURES = 10
        const val LOCKOUT_WINDOW_MS = 5 * 60_000L
    }
}

/** Recognises common photo and video formats from their first bytes. */
object MediaSniffer {
    fun sniff(head: ByteArray): String? {
        fun at(offset: Int, vararg bytes: Int) = head.size >= offset + bytes.size && bytes.indices.all { (head[offset + it].toInt() and 0xff) == bytes[it] }
        fun ascii(offset: Int, text: String) = head.size >= offset + text.length && String(head, offset, text.length, Charsets.ISO_8859_1) == text
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1") || ascii(8, "hevc")) -> "image/heic"
            ascii(4, "ftyp") && ascii(8, "qt  ") -> "video/quicktime"
            ascii(4, "ftyp") -> "video/mp4"
            ascii(0, "%PDF") -> "application/pdf"
            else -> null
        }
    }

    fun withExtension(name: String, mime: String): String {
        if (name.substringAfterLast('/').contains('.')) return name
        val ext = when (mime) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "image/heic" -> "heic"
            "video/quicktime" -> "mov"; "video/mp4" -> "mp4"; "application/pdf" -> "pdf"; else -> return name
        }
        return "$name.$ext"
    }
}

/** QR payload the iPhone camera understands for joining the Android hotspot. */
object WifiQr {
    fun encode(ssid: String, password: String, hidden: Boolean = false): String {
        fun esc(s: String) = s.replace(Regex("""([\;,:"])"""), """\\$1""")
        return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};${if (hidden) "H:true;" else ""};"
    }
}
