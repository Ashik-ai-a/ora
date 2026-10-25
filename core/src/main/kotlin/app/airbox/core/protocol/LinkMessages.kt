package app.airbox.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * "Linked devices" features: what an always-on connection between a paired iPhone and
 * Android phone can carry. Each side advertises what it supports; only the intersection
 * is used, so a feature one OS can't provide is simply absent rather than faked.
 */
@Serializable
enum class LinkFeature {
    /** Mirror notifications from the peer (with actions and, where possible, replies). */
    NOTIFICATIONS,
    /** Send clipboard text on request. Never automatic or in the background. */
    CLIPBOARD,
    /** Open a link / show a text snippet on the other device. */
    LINKS,
    /** Show the peer's battery level. */
    BATTERY,
    /** Make the peer play a sound so it can be found. */
    FIND_DEVICE,
    /** Show and control what's playing on the peer. */
    MEDIA_CONTROL,
}

@Serializable
@SerialName("link_hello")
data class LinkHello(
    val features: Set<LinkFeature>,
    /** How often this side sends keepalives; the peer times out after 3 missed ones. */
    val pingIntervalMs: Long = 15_000,
) : Message

@Serializable
data class NotificationActionInfo(
    val id: String,
    val label: String,
    /** True when the action accepts typed text (e.g. "Reply" on an Android chat notification). */
    val acceptsText: Boolean = false,
)

@Serializable
enum class NotificationKind { MESSAGE, CALL, MISSED_CALL, EMAIL, EVENT, OTHER }

@Serializable
@SerialName("link_notification")
data class MirroredNotification(
    /** Stable for the life of the notification on the source device. */
    val key: String,
    val appId: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAtEpochMs: Long,
    val kind: NotificationKind = NotificationKind.OTHER,
    val actions: List<NotificationActionInfo> = emptyList(),
    val silent: Boolean = false,
) : Message

@Serializable
@SerialName("link_notification_removed")
data class NotificationRemoved(val key: String) : Message

@Serializable
@SerialName("link_notification_action")
data class NotificationActionRequest(val key: String, val actionId: String, val text: String? = null) : Message

@Serializable
@SerialName("link_notification_action_result")
data class NotificationActionResult(val key: String, val actionId: String, val ok: Boolean) : Message

@Serializable
@SerialName("link_clipboard")
data class ClipboardPush(val text: String) : Message

@Serializable
@SerialName("link_url")
data class LinkPush(val url: String? = null, val text: String? = null) : Message

@Serializable
@SerialName("link_battery")
data class BatteryStatus(val percent: Int, val charging: Boolean) : Message

@Serializable
@SerialName("link_ring")
data class RingDevice(val ring: Boolean) : Message

@Serializable
@SerialName("link_media_state")
data class MediaState(
    val appName: String,
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
) : Message

@Serializable
enum class MediaCommand { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

@Serializable
@SerialName("link_media_command")
data class MediaCommandRequest(val command: MediaCommand) : Message

@Serializable
@SerialName("link_ping")
data object LinkPing : Message

/** Which feature a message belongs to; null = always allowed (control/keepalive). */
fun Message.linkFeature(): LinkFeature? = when (this) {
    is MirroredNotification, is NotificationRemoved, is NotificationActionRequest, is NotificationActionResult -> LinkFeature.NOTIFICATIONS
    is ClipboardPush -> LinkFeature.CLIPBOARD
    is LinkPush -> LinkFeature.LINKS
    is BatteryStatus -> LinkFeature.BATTERY
    is RingDevice -> LinkFeature.FIND_DEVICE
    is MediaState, is MediaCommandRequest -> LinkFeature.MEDIA_CONTROL
    else -> null
}
