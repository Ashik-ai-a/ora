package app.airbox.core.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class TrustedDevice(val fingerprint: String, val name: String, val addedEpochMs: Long)

/**
 * Devices the user chose to remember. Platforms should back this with protected storage
 * (Android: app-private storage + Keystore-wrapped key; iOS: Keychain).
 */
interface TrustStore {
    fun isTrusted(fingerprint: String): Boolean
    fun trust(device: TrustedDevice)
    fun revoke(fingerprint: String)
    fun all(): List<TrustedDevice>
}

class InMemoryTrustStore : TrustStore {
    private val devices = linkedMapOf<String, TrustedDevice>()
    @Synchronized override fun isTrusted(fingerprint: String) = fingerprint in devices
    @Synchronized override fun trust(device: TrustedDevice) { devices[device.fingerprint] = device }
    @Synchronized override fun revoke(fingerprint: String) { devices.remove(fingerprint) }
    @Synchronized override fun all() = devices.values.toList()
}

/** JSON file store used by the desktop CLI. Written atomically. */
class FileTrustStore(private val file: Path) : TrustStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val memory = InMemoryTrustStore()

    init {
        if (Files.exists(file)) {
            json.decodeFromString<List<TrustedDevice>>(Files.readString(file)).forEach(memory::trust)
        }
    }

    override fun isTrusted(fingerprint: String) = memory.isTrusted(fingerprint)
    override fun all() = memory.all()
    override fun trust(device: TrustedDevice) { memory.trust(device); save() }
    override fun revoke(fingerprint: String) { memory.revoke(fingerprint); save() }

    @Synchronized
    private fun save() {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(memory.all()))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
