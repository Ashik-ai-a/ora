package app.airbox.android.platform

import app.airbox.core.live.IslandItem
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
)

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
