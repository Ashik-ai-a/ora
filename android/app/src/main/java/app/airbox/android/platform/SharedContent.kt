package app.airbox.android.platform

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.airbox.core.transfer.OutgoingFile
import app.airbox.core.transfer.PathOutgoingFile
import java.io.File
import java.io.InputStream

/** A file shared to Airbox from another app (Gallery, Files, Chrome…), read via its content Uri. */
class UriOutgoingFile(private val resolver: ContentResolver, private val uri: Uri, fallbackMime: String?) : OutgoingFile {
    override val name: String
    override val size: Long
    override val mimeType: String = resolver.getType(uri) ?: fallbackMime ?: "application/octet-stream"
    override val lastModifiedEpochMs: Long? = null

    init {
        var n: String? = null
        var s = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) n = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) s = c.getLong(si)
            }
        }
        name = n ?: uri.lastPathSegment ?: "file"
        size = if (s >= 0) s else resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it >= 0 } ?: 0
    }

    override fun open(): InputStream = resolver.openInputStream(uri) ?: error("cannot open $uri")
}

object SharedContent {
    sealed interface Payload {
        data class Text(val text: String) : Payload
        data class Files(val files: List<OutgoingFile>) : Payload
    }

    /** Parses ACTION_SEND / ACTION_SEND_MULTIPLE from any app. */
    fun parse(context: Context, intent: Intent): Payload? {
        val resolver = context.contentResolver
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri != null) Payload.Files(listOf(UriOutgoingFile(resolver, uri, intent.type)))
                else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { Payload.Text(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                uris?.takeIf { it.isNotEmpty() }?.let { list -> Payload.Files(list.map { UriOutgoingFile(resolver, it, intent.type) }) }
            }
            else -> null
        }
    }

    /**
     * Moves a received file from Airbox's private folder into the public Downloads/Airbox
     * folder so Files, Gallery and other apps see it. Android 10+ uses MediaStore (no
     * permission); 7–9 write directly (WRITE_EXTERNAL_STORAGE, maxSdk 28).
     */
    fun publishToDownloads(context: Context, file: File, mimeType: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Airbox")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Airbox").apply { mkdirs() }
        val target = File(dir, app.airbox.core.transfer.FileNames.uniquify(file.name) { File(dir, it).exists() })
        if (!file.renameTo(target)) { file.copyTo(target, overwrite = false); file.delete() }
        return Uri.fromFile(target)
    }

    /**
     * Saves a received file where people expect it: photos in Pictures/Airbox and videos in
     * Movies/Airbox (so they show up in the Photos / Gallery app straight away), anything
     * else in Downloads/Airbox. Returns a content Uri other apps can open.
     */
    fun publishReceived(context: Context, file: File, mimeType: String): Uri? {
        val isImage = mimeType.startsWith("image/")
        val isVideo = mimeType.startsWith("video/")
        if (!isImage && !isVideo) return publishToDownloads(context, file, mimeType)
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val folder = (if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/Airbox"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(
            if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES), "Airbox").apply { mkdirs() }
        val target = File(dir, app.airbox.core.transfer.FileNames.uniquify(file.name) { File(dir, it).exists() })
        if (!file.renameTo(target)) { file.copyTo(target, overwrite = false); file.delete() }
        // Android 7–9: tell the media scanner so the Gallery sees it now, not after a reboot.
        android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return Uri.fromFile(target)
    }

    fun cacheCopyForSharing(file: File): OutgoingFile = PathOutgoingFile(file.toPath())
}
