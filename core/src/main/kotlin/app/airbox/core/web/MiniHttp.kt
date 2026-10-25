package app.airbox.core.web

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * A deliberately small HTTP/1.1 server core. Android has no built-in HTTP server
 * (com.sun.net.httpserver is JDK-only), and pulling in a framework for a handful of
 * routes would add far more attack surface than it saves. One request per connection.
 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names lowercased. */
    val headers: Map<String, String>,
    val remoteAddress: String,
    /** Exactly Content-Length bytes; reading past it returns -1. */
    val body: InputStream,
    val contentLength: Long,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val cookies: Map<String, String> by lazy {
        header("cookie").orEmpty().split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
    }

    fun readBodyText(limit: Int): String {
        if (contentLength > limit) throw HttpError(413, "Too large")
        val out = ByteArrayOutputStream()
        body.copyTo(out)
        return out.toString(Charsets.UTF_8)
    }
}

class HttpError(val status: Int, message: String) : Exception(message)

class HttpResponse(
    val status: Int,
    val headers: MutableMap<String, String> = linkedMapOf(),
    val body: ByteArray? = null,
    /** For downloads: streamed instead of [body]. */
    val stream: (() -> InputStream)? = null,
    val streamLength: Long = 0,
) {
    companion object {
        fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8") =
            HttpResponse(status, linkedMapOf("Content-Type" to type), text.toByteArray())

        fun json(text: String, status: Int = 200) = text(status, text, "application/json; charset=utf-8")
        fun empty(status: Int = 204) = HttpResponse(status)
        fun redirect(to: String) = HttpResponse(303, linkedMapOf("Location" to to))
    }
}

internal object MiniHttp {
    private const val MAX_HEADER_BYTES = 16 * 1024

    fun parse(input: InputStream, remote: String): HttpRequest {
        val inp = BufferedInputStream(input)
        val head = readHead(inp)
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "Bad request line")
        val headers = lines.drop(1).filter { it.isNotEmpty() }.associate {
            val i = it.indexOf(':')
            if (i <= 0) throw HttpError(400, "Bad header")
            it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw HttpError(411, "Length required")
        val length = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
            // Bodyless methods may omit Content-Length (Safari does for DELETE).
            ?: if (parts[0] in setOf("GET", "HEAD", "DELETE", "OPTIONS")) 0L else throw HttpError(411, "Length required")
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
        return HttpRequest(parts[0], path, query, headers, remote, LimitedInputStream(inp, length), length)
    }

    private fun readHead(inp: InputStream): String {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (true) {
            val b = inp.read()
            if (b < 0) throw IOException("closed before headers")
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
            if (matched == 4) break
            if (buf.size() > MAX_HEADER_BYTES) throw HttpError(431, "Headers too large")
        }
        return buf.toString(Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
    }

    fun write(out: OutputStream, response: HttpResponse, headOnly: Boolean = false) {
        val length = response.body?.size?.toLong() ?: if (response.stream != null) response.streamLength else 0L
        val sb = StringBuilder("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
        val headers = linkedMapOf(
            "Content-Length" to length.toString(),
            "Connection" to "close",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "X-Frame-Options" to "DENY",
        ) + response.headers
        headers.forEach { (k, v) ->
            require('\r' !in v && '\n' !in v) { "header injection" }
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (!headOnly) {
            response.body?.let { out.write(it) }
            response.stream?.invoke()?.use { it.copyTo(out, 64 * 1024) }
        }
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 204 -> "No Content"; 303 -> "See Other"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 411 -> "Length Required"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
        507 -> "Insufficient Storage"; else -> "Error"
    }
}

/** Caps reads at the declared Content-Length so a handler can't read the next request. */
internal class LimitedInputStream(private val inner: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        return inner.read().also { if (it >= 0) remaining-- }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = inner.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    val exhausted get() = remaining == 0L
}
