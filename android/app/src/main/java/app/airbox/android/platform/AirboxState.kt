package app.airbox.android.platform

import app.airbox.core.live.IslandItem
import app.airbox.core.web.PairedDevice
import kotlinx.coroutines.flow.MutableStateFlow
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
)

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
)

object AirboxState {
    private val state = MutableStateFlow(AirboxUiState())
    val flow: StateFlow<AirboxUiState> = state.asStateFlow()

    fun update(change: (AirboxUiState) -> AirboxUiState) = state.update(change)

    fun addRecent(item: RecentTransfer) = state.update { it.copy(recent = (listOf(item) + it.recent).take(30)) }
}
