package app.airbox.core.companion

import app.airbox.core.protocol.*

/**
 * Decides what leaves the device. Notifications are among the most private data on a
 * phone, so mirroring is opt-in per app and content can be hidden.
 */
data class NotificationPolicy(
    /** Apps whose notifications are mirrored. Empty = nothing is mirrored (opt-in). */
    val allowedApps: Set<String> = emptySet(),
    /** Apps mirrored as "New notification" without title/text. */
    val hideContentApps: Set<String> = emptySet(),
    val mirrorSilent: Boolean = false,
    /** Mask one-time passcodes ("Your code is 482913"). */
    val maskOneTimeCodes: Boolean = true,
    val maxTextChars: Int = 1_000,
) {
    fun apply(n: MirroredNotification): MirroredNotification? {
        if (n.appId !in allowedApps) return null
        if (n.silent && !mirrorSilent) return null
        if (n.appId in hideContentApps) {
            return n.copy(title = n.appName, text = "New notification", actions = n.actions.filter { !it.acceptsText })
        }
        var text = n.text.take(maxTextChars)
        var title = n.title.take(200)
        if (maskOneTimeCodes && looksLikeOtp(title + " " + text)) {
            text = OTP_DIGITS.replace(text) { "•".repeat(it.value.length) }
            title = OTP_DIGITS.replace(title) { "•".repeat(it.value.length) }
        }
        return n.copy(title = title, text = text)
    }

    private fun looksLikeOtp(s: String) = OTP_HINT.containsMatchIn(s) && OTP_DIGITS.containsMatchIn(s)

    companion object {
        private val OTP_HINT = Regex("""\b(code|otp|passcode|verification|verify|2fa|one[- ]time|pin)\b""", RegexOption.IGNORE_CASE)
        private val OTP_DIGITS = Regex("""(?<![\d-])\d{4,8}(?![\d-])""")
    }
}
