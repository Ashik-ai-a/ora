package app.airbox.core

import app.airbox.core.transfer.DirectoryReceiveStorage
import app.airbox.core.transfer.PathOutgoingFile
import app.airbox.core.transfer.ReceiveStorage
import app.airbox.core.web.WebBridge
import app.airbox.core.web.WifiQr
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The iPhone side here is a plain HTTP client, exactly what Safari / Shortcuts are. */
class WebBridgeTest {
    private val tmp = Files.createTempDirectory("airbox-web")
    private val out = Files.createDirectories(tmp.resolve("in"))
    private val texts = LinkedBlockingQueue<String>()
    private var storage: ReceiveStorage = DirectoryReceiveStorage(out)
    private val bridge = WebBridge(object : ReceiveStorage by DirectoryReceiveStorage(out) {
        override fun freeBytes() = storage.freeBytes()
    }, "Pixel 9", onText = { texts += it }, bindAddress = "127.0.0.1").start()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val base get() = "http://127.0.0.1:${bridge.port}"

    @AfterTest fun cleanup() { bridge.close(); tmp.toFile().deleteRecursively() }

    private fun get(path: String, cookie: String? = null): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI("$base$path")).apply { cookie?.let { header("Cookie", it) } }.GET().build(),
            HttpResponse.BodyHandlers.ofByteArray())

    private fun post(path: String, body: ByteArray, cookie: String? = null, csrf: Boolean = true, auth: String? = null) =
        http.send(HttpRequest.newBuilder(URI("$base$path")).apply {
            cookie?.let { header("Cookie", it) }
            if (csrf) header("X-Airbox", "1")
            auth?.let { header("Authorization", "Bearer $it") }
        }.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString())

    /** What happens when the iPhone camera opens the QR link. */
    private fun scanQr(): String {
        val url = bridge.pairingUrl("127.0.0.1")
        val r = http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.discarding())
        assertEquals(303, r.statusCode())
        assertEquals("/", r.headers().firstValue("Location").get())
        val setCookie = r.headers().firstValue("Set-Cookie").get()
        assertTrue("HttpOnly" in setCookie && "SameSite=Strict" in setCookie)
        return setCookie.substringBefore(';')
    }

    @Test fun iphoneCopiesTextAndAndroidReceivesIt() {
        val cookie = scanQr()
        assertEquals(200, get("/", cookie).statusCode())
        assertEquals(204, post("/api/text", "Meet at Gate 22 ✈️".toByteArray(), cookie).statusCode())
        assertEquals("Meet at Gate 22 ✈️", texts.poll(2, TimeUnit.SECONDS))
    }

    @Test fun qrLinkWorksOnceAndPageRequiresIt() {
        val url = bridge.pairingUrl("127.0.0.1")
        http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.discarding())
        val reused = http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(401, reused.statusCode())
        assertTrue("Scan the code again" in reused.body())
        assertTrue("Scan the code again" in String(get("/").body()))
        assertEquals(401, post("/api/text", "x".toByteArray()).statusCode())
        assertTrue(texts.isEmpty())
    }

    @Test fun crossSiteRequestsAreRefused() {
        val cookie = scanQr()
        // A malicious web page can't add the custom header without a CORS preflight we never answer.
        assertEquals(403, post("/api/text", "evil".toByteArray(), cookie, csrf = false).statusCode())
        // DNS rebinding: a public hostname that resolves to the phone is refused.
        java.net.Socket("127.0.0.1", bridge.port).use { sock ->
            sock.getOutputStream().write(
                "POST /api/text HTTP/1.1\r\nHost: evil.example.com\r\nCookie: $cookie\r\nX-Airbox: 1\r\nContent-Length: 1\r\n\r\nx".toByteArray(),
            )
            val status = sock.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 403 Forbidden", status)
        }
        assertTrue(texts.isEmpty())
    }

    @Test fun shortcutKeySendsClipboardAndCanBeRevoked() {
        val key = bridge.createShortcutKey("Ana's iPhone")
        assertEquals(204, post("/api/text", "from Back Tap".toByteArray(), csrf = false, auth = key).statusCode())
        assertEquals("from Back Tap", texts.poll(2, TimeUnit.SECONDS))
        bridge.revokeShortcutKey(key)
        assertEquals(401, post("/api/text", "again".toByteArray(), csrf = false, auth = key).statusCode())
    }

    @Test fun photoUploadIsSavedIntactWithSafeName() {
        val cookie = scanQr()
        val photo = Random(1).nextBytes(2_500_000)
        val r = post("/api/upload?name=" + java.net.URLEncoder.encode("../IMG_0042.HEIC", Charsets.UTF_8), photo, cookie)
        assertEquals(200, r.statusCode())
        assertTrue("IMG_0042.HEIC" in r.body())
        assertContentEquals(photo, out.resolve("IMG_0042.HEIC").readBytes())
        assertEquals(setOf("IMG_0042.HEIC"), out.listDirectoryEntries().filter { !it.name.startsWith(".") }.map { it.name }.toSet())
    }

    @Test fun uploadRefusedWhenAndroidIsFull() {
        val cookie = scanQr()
        storage = object : ReceiveStorage by DirectoryReceiveStorage(out) { override fun freeBytes() = 1_000L }
        assertEquals(507, post("/api/upload?name=a.mov", ByteArray(10_000), cookie).statusCode())
    }

    @Test fun androidSharesTextAndFilesToIphone() {
        val cookie = scanQr()
        assertEquals("null", String(get("/api/text", cookie).body()))
        bridge.shareText("Wi-Fi password: hunter2")
        assertTrue("hunter2" in String(get("/api/text", cookie).body()))
        val src = tmp.resolve("boarding pass.pdf").also { Files.write(it, Random(2).nextBytes(50_000)) }
        val id = bridge.shareFile(PathOutgoingFile(src))
        assertTrue(id in String(get("/api/files", cookie).body()))
        val dl = get("/api/files/$id", cookie)
        assertEquals(200, dl.statusCode())
        assertContentEquals(src.readBytes(), dl.body())
        assertTrue("filename*=UTF-8''boarding%20pass.pdf" in dl.headers().firstValue("Content-Disposition").get())
        assertEquals(401, get("/api/files/$id").statusCode())
    }

    @Test fun guessingTokensLocksTheAddressOut() {
        repeat(WebBridge.MAX_FAILURES) { get("/?t=guess$it") }
        val url = bridge.pairingUrl("127.0.0.1")
        val r = http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.discarding())
        assertEquals(429, r.statusCode())
    }

    @Test fun pageHasStrictSecurityHeaders() {
        val r = get("/", scanQr())
        assertTrue("script-src 'self'" in r.headers().firstValue("Content-Security-Policy").get())
        assertEquals("no-store", r.headers().firstValue("Cache-Control").get())
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").get())
    }

    @Test fun wifiQrEscapesSpecialCharacters() {
        assertEquals("""WIFI:T:WPA;S:Airbox\;Ana;P:p\:ss\,w;;""", WifiQr.encode("Airbox;Ana", "p:ss,w"))
    }
}
