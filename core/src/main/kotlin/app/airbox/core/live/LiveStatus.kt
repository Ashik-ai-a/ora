package app.airbox.core.live

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What Airbox shows on the iPhone Dynamic Island (ActivityKit Live Activities) and the
 * Android 16 status-bar chip (Live Updates). Both platforms implement these same rules so
 * the two phones behave alike. The Swift port lives in ios/AirboxKit/IslandPolicy.swift.
 *
 * Platform rules this respects:
 *  - Live Activities are for events with a start and an end, not a permanent dashboard,
 *    and stay at most 8 hours on the island. So "linked to Pixel, 64%" is never shown on its
 *    own: battery rides along inside a real activity.
 *  - The island shows at most two activities; we rank and keep the top [maxVisible].
 *  - Updates cost battery; progress is throttled.
 *  - ActivityKit content state must stay small (≈4 KB); text is trimmed.
 */
@Serializable
enum class IslandKind(val priority: Int) {
    INCOMING_CALL(100),   // Android phone ringing with a call
    FIND_DEVICE(90),      // Android phone is playing the "find me" sound
    TRANSFER(70),         // files moving either way
    CLIPBOARD(50),        // "Copied to Pixel ✓" / "Text from Pixel – tap to copy"
    NOW_PLAYING(30),      // media playing on the Android phone
}

@Serializable
enum class IslandAction { CANCEL, DECLINE_CALL, STOP_RING, PLAY_PAUSE, NEXT, PREVIOUS, COPY, OPEN }

@Serializable
data class IslandItem(
    val id: String,
    val kind: IslandKind,
    /** Compact island text: very short (e.g. "42%", "Mum", "✓"). */
    val compact: String,
    val title: String,
    val subtitle: String = "",
    /** 0..1, or null when not a progress activity. */
    val progress: Double? = null,
    val actions: List<IslandAction> = emptyList(),
    val peerName: String,
    val peerBatteryPercent: Int? = null,
    val startedAtEpochMs: Long,
    /** When the item should disappear on its own (null = until ended). */
    val endsAtEpochMs: Long? = null,
)

sealed interface IslandEvent {
    data class TransferProgress(val transferId: String, val incoming: Boolean, val fileName: String, val fileCount: Int,
                                val done: Long, val total: Long, val bytesPerSecond: Double) : IslandEvent
    data class TransferFinished(val transferId: String, val incoming: Boolean, val ok: Boolean, val summary: String) : IslandEvent
    data class ClipboardSent(val preview: String) : IslandEvent
    data class ClipboardReceived(val preview: String) : IslandEvent
    data class CallStarted(val callId: String, val caller: String) : IslandEvent
    data class CallEnded(val callId: String) : IslandEvent
    data class RingStarted(val reason: String = "Playing sound") : IslandEvent
    data object RingStopped : IslandEvent
    data class NowPlaying(val title: String, val artist: String?, val playing: Boolean) : IslandEvent
    data object MediaStopped : IslandEvent
    data class PeerBattery(val percent: Int) : IslandEvent
    data class Tick(val nowEpochMs: Long) : IslandEvent
}

/**
 * Pure state machine: feed events, get the items to display and whether the platform
 * should push an update now. No timers or OS calls; the app owns those.
 */
class IslandModel(
    private val peerName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxVisible: Int = 2,
    private val minProgressUpdateMs: Long = 1_000,
    private val briefMs: Long = 4_000,
    private val finishedMs: Long = 6_000,
    private val maxLifetimeMs: Long = 8 * 60 * 60_000L,
) {
    private val items = linkedMapOf<String, IslandItem>()
    private var battery: Int? = null
    private val lastPushed = mutableMapOf<String, Pair<Long, Double?>>()

    /** Items in display order (highest priority first, then most recent). */
    val visible: List<IslandItem>
        get() = items.values.sortedWith(compareByDescending<IslandItem> { it.kind.priority }.thenByDescending { it.startedAtEpochMs })
            .take(maxVisible)

    /** All live items, including ones not currently on screen. */
    val all: List<IslandItem> get() = items.values.toList()

    /**
     * Applies [event]. Returns true when the visible state changed enough that the platform
     * should update the Live Activity / notification now.
     */
    fun apply(event: IslandEvent): Boolean {
        val now = clock()
        val before = visible
        when (event) {
            is IslandEvent.TransferProgress -> {
                val id = "transfer:${event.transferId}"
                val fraction = if (event.total <= 0) 0.0 else (event.done.toDouble() / event.total).coerceIn(0.0, 1.0)
                val pct = (fraction * 100).toInt()
                val existing = items[id]
                val eta = if (event.bytesPerSecond >= 1 && event.done > 0) ((event.total - event.done) / event.bytesPerSecond).toLong() else null
                items[id] = IslandItem(
                    id, IslandKind.TRANSFER, compact = "$pct%",
                    title = (if (event.incoming) "From $peerName" else "To $peerName") +
                        if (event.fileCount > 1) " · ${event.fileCount} files" else "",
                    subtitle = listOfNotNull(event.fileName, eta?.let { formatEta(it) }).joinToString(" · "),
                    progress = fraction, actions = listOf(IslandAction.CANCEL), peerName = peerName,
                    peerBatteryPercent = battery, startedAtEpochMs = existing?.startedAtEpochMs ?: now,
                )
                if (existing != null && before.any { it.id == id }) return throttled(id, fraction, now)
                lastPushed[id] = now to fraction
            }
            is IslandEvent.TransferFinished -> {
                val id = "transfer:${event.transferId}"
                lastPushed.remove(id)
                val existing = items[id]
                items[id] = IslandItem(
                    id, IslandKind.TRANSFER, compact = if (event.ok) "✓" else "!",
                    title = if (event.ok) (if (event.incoming) "Received from $peerName" else "Sent to $peerName") else "Transfer stopped",
                    subtitle = trim(event.summary, 80), progress = if (event.ok) 1.0 else existing?.progress,
                    actions = if (event.incoming && event.ok) listOf(IslandAction.OPEN) else emptyList(),
                    peerName = peerName, peerBatteryPercent = battery,
                    startedAtEpochMs = existing?.startedAtEpochMs ?: now, endsAtEpochMs = now + finishedMs,
                )
            }
            is IslandEvent.ClipboardSent -> put(IslandItem("clipboard", IslandKind.CLIPBOARD, "✓", "Copied to $peerName",
                trim(event.preview, 60), peerName = peerName, peerBatteryPercent = battery, startedAtEpochMs = now, endsAtEpochMs = now + briefMs))
            is IslandEvent.ClipboardReceived -> put(IslandItem("clipboard", IslandKind.CLIPBOARD, "Text", "Text from $peerName",
                trim(event.preview, 60), actions = listOf(IslandAction.COPY), peerName = peerName, peerBatteryPercent = battery,
                startedAtEpochMs = now, endsAtEpochMs = now + 30_000))
            is IslandEvent.CallStarted -> put(IslandItem("call:${event.callId}", IslandKind.INCOMING_CALL, trim(event.caller, 12),
                trim(event.caller, 40), "Calling $peerName", actions = listOf(IslandAction.DECLINE_CALL), peerName = peerName,
                peerBatteryPercent = battery, startedAtEpochMs = now))
            is IslandEvent.CallEnded -> items.remove("call:${event.callId}")
            is IslandEvent.RingStarted -> put(IslandItem("ring", IslandKind.FIND_DEVICE, "🔔", "$peerName is ringing",
                event.reason, actions = listOf(IslandAction.STOP_RING), peerName = peerName, peerBatteryPercent = battery,
                startedAtEpochMs = now, endsAtEpochMs = now + 2 * 60_000))
            IslandEvent.RingStopped -> items.remove("ring")
            is IslandEvent.NowPlaying -> put(IslandItem("media", IslandKind.NOW_PLAYING, if (event.playing) "▶" else "⏸",
                trim(event.title, 40), trim(event.artist.orEmpty(), 40),
                actions = listOf(IslandAction.PREVIOUS, IslandAction.PLAY_PAUSE, IslandAction.NEXT), peerName = peerName,
                peerBatteryPercent = battery, startedAtEpochMs = items["media"]?.startedAtEpochMs ?: now))
            IslandEvent.MediaStopped -> items.remove("media")
            is IslandEvent.PeerBattery -> {
                battery = event.percent.coerceIn(0, 100)
                items.replaceAll { _, v -> v.copy(peerBatteryPercent = battery) }
                return false // battery alone never warrants an update
            }
            is IslandEvent.Tick -> Unit
        }
        expire(now)
        return visible != before
    }

    private fun put(item: IslandItem) { items[item.id] = item }

    private fun expire(now: Long) {
        items.entries.removeIf { (_, v) ->
            (v.endsAtEpochMs != null && v.endsAtEpochMs <= now) || now - v.startedAtEpochMs >= maxLifetimeMs
        }
    }

    private fun throttled(id: String, fraction: Double, now: Long): Boolean {
        val (at, value) = lastPushed[id] ?: (0L to null)
        val bigJump = value == null || fraction - value >= 0.05 || fraction >= 1.0
        if (now - at >= minProgressUpdateMs || bigJump) {
            lastPushed[id] = now to fraction
            return true
        }
        return false
    }

    companion object {
        const val MAX_STATE_BYTES = 4_096
        // Defaults included: Swift Codable does not fill in missing keys.
        private val json = Json { encodeDefaults = true }

        fun trim(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

        fun formatEta(seconds: Long): String = when {
            seconds < 60 -> "${seconds}s left"
            seconds < 3600 -> "${seconds / 60} min left"
            else -> "${seconds / 3600} h left"
        }

        /** Encoded content state; guaranteed under the ActivityKit size limit. */
        fun encode(item: IslandItem): String {
            var s = json.encodeToString(item)
            if (s.toByteArray().size > MAX_STATE_BYTES) {
                s = json.encodeToString(item.copy(title = trim(item.title, 40), subtitle = trim(item.subtitle, 40)))
            }
            check(s.toByteArray().size <= MAX_STATE_BYTES)
            return s
        }
    }
}
