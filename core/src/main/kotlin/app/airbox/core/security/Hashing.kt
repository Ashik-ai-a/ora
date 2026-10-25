package app.airbox.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal val secureRandom = SecureRandom()

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun sha256(vararg parts: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { digest.update(it) }
    return digest.digest()
}

fun randomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

fun String.fromBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

/** Constant-time comparison so token checks don't leak a timing side channel. */
fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
