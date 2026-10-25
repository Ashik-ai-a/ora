package app.airbox.android.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Puts text from the iPhone on the Android clipboard. Writing is allowed from a
 * foreground service on every version; Android 13+ shows its own "Copied" confirmation.
 */
object Clipboard {
    private val OTP = Regex("""\b(code|otp|passcode|verification|pin)\b""", RegexOption.IGNORE_CASE)

    fun put(context: Context, text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("From iPhone", text)
        // Mark likely one-time codes as sensitive so Android 13+ hides them in the preview.
        if (OTP.containsMatchIn(text)) {
            clip.description.extras = PersistableBundle().apply {
                if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                else putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        manager.setPrimaryClip(clip)
    }

    /**
     * Puts an image on the clipboard as a content Uri, so the keyboard's clipboard (Gboard,
     * Samsung Keyboard, SwiftKey) can paste it into chats, notes and other apps.
     */
    fun putImage(context: Context, uri: android.net.Uri) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newUri(context.contentResolver, "Image from iPhone", uri))
    }

    fun read(context: Context): String? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}
