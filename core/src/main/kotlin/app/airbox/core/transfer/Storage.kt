package app.airbox.core.transfer

import app.airbox.core.security.sha256
import app.airbox.core.security.toHex
import app.airbox.core.protocol.FileEntry
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A file the user chose to send. Android implements this over a content:// Uri. */
interface OutgoingFile {
    val name: String
    val size: Long
    val mimeType: String
    val lastModifiedEpochMs: Long?

    /** Opens the content positioned at byte 0. */
    fun open(): InputStream
}

class PathOutgoingFile(private val path: Path, override val mimeType: String = guessMime(path.name)) : OutgoingFile {
    override val name: String = path.name
    override val size: Long = path.fileSize()
    override val lastModifiedEpochMs: Long = path.getLastModifiedTime().toMillis()
    override fun open(): InputStream = Files.newInputStream(path)

    companion object {
        fun guessMime(name: String): String =
            java.net.URLConnection.guessContentTypeFromName(name) ?: when (name.substringAfterLast('.', "").lowercase()) {
                "heic" -> "image/heic"
                "webp" -> "image/webp"
                "mov" -> "video/quicktime"
                "mp4" -> "video/mp4"
                "pdf" -> "application/pdf"
                "vcf" -> "text/vcard"
                else -> "application/octet-stream"
            }
    }
}

/** Data received so far for one file. Kept across reconnects so transfers can resume. */
interface PartialFile {
    val length: Long
    fun openAppend(): OutputStream
    fun openRead(): InputStream
    fun delete()
}

/**
 * Where received files go. The JVM implementation writes to a directory; Android would
 * implement this with MediaStore / SAF, iOS with the app container + Files/Photos export.
 */
interface ReceiveStorage {
    fun freeBytes(): Long
    fun partial(transferId: String, entry: FileEntry): PartialFile

    /**
     * Atomically publishes a verified partial under a non-clashing variant of [safeName].
     * Never overwrites. Returns the final name.
     */
    fun publish(partial: PartialFile, safeName: String, entry: FileEntry): String

    /** Deletes every partial of a transfer (cancel / reject). */
    fun discard(transferId: String)

    /** Removes partials nobody resumed (call periodically). */
    fun purgeStale(maxAge: Duration)
}

class DirectoryReceiveStorage(private val destination: Path) : ReceiveStorage {
    private val partialRoot: Path = destination.resolve(".airbox-partial")

    init {
        Files.createDirectories(partialRoot)
    }

    override fun freeBytes(): Long = Files.getFileStore(destination).usableSpace

    override fun partial(transferId: String, entry: FileEntry): PartialFile {
        require(transferId.matches(TRANSFER_ID)) { "invalid transfer id" }
        // Key by content metadata so a reused transferId with a different file is never spliced.
        val key = sha256("${entry.index}|${entry.name}|${entry.size}|${entry.lastModifiedEpochMs}".toByteArray()).toHex().take(32)
        return PathPartial(partialRoot.resolve(transferId).resolve("$key.part"))
    }

    override fun publish(partial: PartialFile, safeName: String, entry: FileEntry): String {
        val source = (partial as PathPartial).path
        while (true) {
            val name = FileNames.uniquify(safeName) { destination.resolve(it).exists() }
            val target = destination.resolve(name)
            try {
                // Reserve the name atomically (CREATE_NEW), then atomically replace our own
                // empty placeholder. A plain rename could silently replace someone else's file.
                Files.createFile(target)
            } catch (e: FileAlreadyExistsException) {
                continue // Lost a race with another writer; pick the next free name.
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.deleteIfExists(target)
                throw AirboxException(ErrorCode.STORAGE_ERROR, "partial directory is on another filesystem", e)
            }
            entry.lastModifiedEpochMs?.let {
                runCatching { Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(it)) }
            }
            return name
        }
    }

    override fun discard(transferId: String) {
        if (!transferId.matches(TRANSFER_ID)) return
        val dir = partialRoot.resolve(transferId)
        if (!dir.exists()) return
        dir.listDirectoryEntries().forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(dir)
    }

    override fun purgeStale(maxAge: Duration) {
        val cutoff = Instant.now().minus(maxAge)
        partialRoot.listDirectoryEntries().forEach { dir ->
            if (Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff)) discard(dir.name)
        }
    }

    private class PathPartial(val path: Path) : PartialFile {
        override val length: Long get() = if (path.exists()) path.fileSize() else 0
        override fun openAppend(): OutputStream {
            Files.createDirectories(path.parent)
            return Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        }
        override fun openRead(): InputStream = Files.newInputStream(path)
        override fun delete() { Files.deleteIfExists(path) }
    }

    companion object {
        val TRANSFER_ID = Regex("[A-Za-z0-9_-]{8,64}")
    }
}
