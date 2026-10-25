package app.airbox.core.transfer

import java.text.Normalizer

/**
 * Turns an untrusted, peer-supplied file name into a safe single path component.
 * Never trust the sender: strips directories, traversal, control and bidi-override
 * characters (used to disguise "exe.pdf" style names), and reserved Windows names.
 */
object FileNames {
    private const val MAX_UTF8_BYTES = 200 // leaves room for " (123)" and temp suffixes under 255.
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val bidiControls = Regex("[‎‏‪-‮⁦-⁩]")
    private val reserved = Regex("""^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$""", RegexOption.IGNORE_CASE)

    fun sanitize(raw: String): String {
        var name = Normalizer.normalize(raw, Normalizer.Form.NFC)
        name = name.substringAfterLast('/').substringAfterLast('\\')
        name = bidiControls.replace(name, "")
        name = forbidden.replace(name, "_")
        name = name.trim().trimStart('.').trimEnd('.', ' ')
        if (name.isEmpty()) name = "file"
        if (reserved.matches(name)) name = "_$name"
        return truncateUtf8(name)
    }

    /** Keeps the extension when truncating, so the file still opens in the right app. */
    private fun truncateUtf8(name: String): String {
        if (name.toByteArray().size <= MAX_UTF8_BYTES) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        var base = name.removeSuffix(ext)
        while ((base + ext).toByteArray().size > MAX_UTF8_BYTES && base.isNotEmpty()) {
            base = base.dropLast(1)
            // Do not cut a surrogate pair in half.
            if (base.isNotEmpty() && base.last().isHighSurrogate()) base = base.dropLast(1)
        }
        return (base.ifEmpty { "file" }) + ext
    }

    /** "photo.jpg" -> "photo (1).jpg", "photo (2).jpg", ... until [exists] says no. */
    fun uniquify(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        for (i in 1..9_999) {
            val candidate = "$base ($i)$ext"
            if (!exists(candidate)) return candidate
        }
        throw AirboxException(ErrorCode.STORAGE_ERROR, "too many duplicates")
    }
}
