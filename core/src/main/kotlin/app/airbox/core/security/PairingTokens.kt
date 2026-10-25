package app.airbox.core.security

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One-time pairing tokens shown by the receiver as a QR code / deep link. The link also
 * carries the receiver's certificate fingerprint, so the sender authenticates the receiver
 * (pinning) while the token authenticates the sender's intent to the receiver.
 */
class PairingTokenIssuer(
    private val ttlMillis: Long = 2 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Issued(val token: String, val expiresAt: Long)

    private val live = mutableListOf<Issued>()
    private var failures = 0

    @Synchronized
    fun issue(): String {
        prune()
        return randomBytes(16).toBase64Url().also { live += Issued(it, clock() + ttlMillis) }
    }

    /** Consumes the token on success. Locks out all tokens after repeated failures. */
    @Synchronized
    fun redeem(candidate: String): Boolean {
        prune()
        if (failures >= MAX_FAILURES) return false
        val match = live.firstOrNull { constantTimeEquals(it.token, candidate) }
        return if (match != null) {
            live.remove(match)
            true
        } else {
            failures++
            if (failures >= MAX_FAILURES) live.clear()
            false
        }
    }

    @Synchronized
    fun revokeAll() {
        live.clear()
        failures = 0
    }

    private fun prune() {
        val now = clock()
        live.removeAll { it.expiresAt <= now }
    }

    companion object {
        const val MAX_FAILURES = 5
    }
}

data class PairingLink(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val deviceName: String,
) {
    fun toUri(): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        return "airbox://pair?v=1&h=${enc(host)}&p=$port&fp=$fingerprint&t=${enc(token)}&n=${enc(deviceName)}"
    }

    companion object {
        fun parse(uri: String): PairingLink {
            val parsed = URI(uri)
            require(parsed.scheme == "airbox" && parsed.host == "pair") { "not an Airbox pairing link" }
            val params = parsed.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
            require(params["v"] == "1") { "unsupported pairing link version" }
            val fingerprint = params["fp"].orEmpty()
            require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "invalid fingerprint" }
            return PairingLink(
                host = requireNotNull(params["h"]) { "missing host" },
                port = requireNotNull(params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 }) { "invalid port" },
                fingerprint = fingerprint,
                token = requireNotNull(params["t"]) { "missing token" },
                deviceName = params["n"].orEmpty(),
            )
        }
    }
}
