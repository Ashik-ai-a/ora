package app.airbox.android.platform

import android.content.Context
import android.net.Uri
import app.airbox.core.live.IslandItem
import app.airbox.core.web.PairedDevice
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the UI shows. Owned by [AirboxService]; the UI only observes it. */
data class AirboxUiState(
    val running: Boolean = false,
    val wifiAddress: String? = null,
    val pairingUrl: String? = null,
    val hotspot: HotspotInfo? = null,
    val iphoneSeen: Boolean = false,
    val lastText: String? = null,
    val sending: IslandItem? = null,
    val recent: List<RecentTransfer> = emptyList(),
    /** Shares waiting for Accept / Decline, one per prompt (a batch of photos is one prompt). */
    val prompts: List<IncomingPrompt> = emptyList(),
    /** Phones that paired once and never need to scan again. */
    val paired: List<PairedDevice> = emptyList(),
    /** Text sent either way, oldest first, like a chat. */
    val messages: List<TextMessage> = emptyList(),
)

data class TextMessage(val text: String, val incoming: Boolean, val atEpochMs: Long)

data class IncomingPrompt(
    val promptId: String,
    val firstTransferId: String,
    val from: String,
    val fromDeviceId: String,
    val names: List<String>,
    val totalBytes: Long,
    val mimeTypes: List<String>,
) {
    val summary: String get() {
        val n = names.size
        val photos = mimeTypes.count { it.startsWith("image/") }
        val videos = mimeTypes.count { it.startsWith("video/") }
        return when {
            n == 1 -> names[0]
            photos == n -> "$n photos"
            videos == n -> "$n videos"
            photos + videos == n -> "$n photos and videos"
            else -> "$n items"
        }
    }
}

data class HotspotInfo(val ssid: String, val password: String, val wifiQr: String)

data class RecentTransfer(
    val name: String,
    val incoming: Boolean,
    val sizeBytes: Long,
    val atEpochMs: Long,
    /** Where a received file was saved (Photos / Downloads), so it can be deleted from the list. */
    val uri: String? = null,
) {
    val key: String get() = "$atEpochMs/$name"
    val canDeleteFile: Boolean get() = incoming && uri != null
}

object AirboxState {
    private const val MAX_RECENT = 200
    private const val MAX_MESSAGES = 100
    private val state = MutableStateFlow(AirboxUiState())
    val flow: StateFlow<AirboxUiState> = state.asStateFlow()
    @Volatile private var recentFile: File? = null
    @Volatile private var messageFile: File? = null

    fun update(change: (AirboxUiState) -> AirboxUiState) = state.update(change)

    /** Newest first, kept across restarts. */
    fun addRecent(item: RecentTransfer) {
        state.update { s -> s.copy(recent = (listOf(item) + s.recent).sortedByDescending { it.atEpochMs }.take(MAX_RECENT)) }
        save()
    }

    /**
     * Swipe-to-delete: removes the row and, for a received file, the file itself (from
     * Photos / Downloads). Airbox created those files, so no extra permission is needed.
     * Returns false if the file couldn't be deleted (the row stays).
     */
    fun deleteRecent(context: Context, item: RecentTransfer): Boolean {
        if (item.canDeleteFile) {
            val deleted = runCatching {
                val uri = Uri.parse(item.uri)
                if (uri.scheme == "file") File(uri.path ?: "").let { !it.exists() || it.delete() }
                else context.contentResolver.delete(uri, null, null) >= 0
            }.getOrDefault(false)
            if (!deleted) return false
        }
        state.update { s -> s.copy(recent = s.recent.filterNot { it.key == item.key }) }
        save()
        return true
    }

    /** Removes the row only, keeping the file. */
    fun removeRecent(item: RecentTransfer) {
        state.update { s -> s.copy(recent = s.recent.filterNot { it.key == item.key }) }
        save()
    }

    fun addMessage(message: TextMessage) {
        state.update { s -> s.copy(messages = (s.messages + message).takeLast(MAX_MESSAGES)) }
        saveMessages()
    }

    fun clearMessages() {
        state.update { it.copy(messages = emptyList()) }
        saveMessages()
    }

    fun attachMessageStore(file: File) {
        messageFile = file
        val loaded = runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TextMessage(o.getString("text"), o.getBoolean("incoming"), o.getLong("at"))
            }
        }.getOrDefault(emptyList())
        state.update { s -> s.copy(messages = (loaded + s.messages).distinct().sortedBy { it.atEpochMs }.takeLast(MAX_MESSAGES)) }
    }

    private fun saveMessages() {
        val file = messageFile ?: return
        val array = JSONArray()
        state.value.messages.forEach { m -> array.put(JSONObject().put("text", m.text).put("incoming", m.incoming).put("at", m.atEpochMs)) }
        writeAtomically(file, array.toString())
    }

    private fun writeAtomically(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    /** Loads the history saved by earlier runs. Call once when the service starts. */
    fun attachRecentStore(file: File) {
        recentFile = file
        val loaded = runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RecentTransfer(o.getString("name"), o.getBoolean("incoming"), o.getLong("size"), o.getLong("at"),
                    o.optString("uri").ifBlank { null })
            }
        }.getOrDefault(emptyList())
        state.update { s -> s.copy(recent = (s.recent + loaded).distinctBy { it.key }.sortedByDescending { it.atEpochMs }.take(MAX_RECENT)) }
    }

    private fun save() {
        val file = recentFile ?: return
        val array = JSONArray()
        state.value.recent.forEach { r ->
            array.put(JSONObject().put("name", r.name).put("incoming", r.incoming).put("size", r.sizeBytes).put("at", r.atEpochMs)
                .put("uri", r.uri ?: ""))
        }
        writeAtomically(file, array.toString())
    }
}
