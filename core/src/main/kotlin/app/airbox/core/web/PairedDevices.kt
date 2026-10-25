package app.airbox.core.web

import app.airbox.core.security.sha256
import app.airbox.core.security.toHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A phone that paired once (QR scan, the iPhone app, or a Shortcut key) and is remembered
 * from then on, so nobody has to scan again. Only a hash of its key is stored, so a copy
 * of this file can't be used to impersonate the phone.
 */
@Serializable
data class PairedDevice(
    val id: String,
    val keyHash: String,
    val name: String,
    /** Skip the Accept / Decline prompt for this device ("Always accept"). */
    val autoAccept: Boolean = false,
    val pairedAtEpochMs: Long,
    val lastSeenEpochMs: Long = pairedAtEpochMs,
) {
    companion object {
        fun hash(key: String): String = sha256(key.toByteArray()).toHex()
    }
}

interface PairedDeviceStore {
    fun load(): List<PairedDevice>
    fun save(devices: List<PairedDevice>)
}

class InMemoryPairedDeviceStore : PairedDeviceStore {
    @Volatile private var devices: List<PairedDevice> = emptyList()
    override fun load() = devices
    override fun save(devices: List<PairedDevice>) { this.devices = devices }
}

/** JSON file in app-private storage, written atomically. */
class FilePairedDeviceStore(private val file: Path) : PairedDeviceStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PairedDevice.serializer())

    override fun load(): List<PairedDevice> =
        if (Files.exists(file)) runCatching { json.decodeFromString(serializer, Files.readString(file)) }.getOrDefault(emptyList())
        else emptyList()

    @Synchronized
    override fun save(devices: List<PairedDevice>) {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(serializer, devices))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
