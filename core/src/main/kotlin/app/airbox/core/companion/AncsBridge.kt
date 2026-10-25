package app.airbox.core.companion

import app.airbox.core.protocol.*

import java.time.ZoneId

/**
 * Turns ANCS events from a bonded iPhone into [MirroredNotification]s for the Android UI,
 * and maps user actions back to ANCS commands. One request is sent at a time, as ANCS requires.
 */
class AncsBridge(
    /** Writes bytes to the ANCS Control Point characteristic. */
    private val writeControlPoint: (ByteArray) -> Unit,
    private val onNotification: (MirroredNotification) -> Unit,
    private val onRemoved: (String) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val assembler = Ancs.DataSourceAssembler()
    private val pending = ArrayDeque<Ancs.SourceEvent>()
    private var inFlight: Ancs.SourceEvent? = null
    private val appNames = mutableMapOf<String, String>()
    private var awaitingAppName: Pair<Ancs.SourceEvent, Ancs.NotificationAttributes>? = null
    private val known = mutableMapOf<Long, Ancs.SourceEvent>()

    private val requested = listOf(
        Ancs.NotificationAttribute.APP_IDENTIFIER, Ancs.NotificationAttribute.TITLE, Ancs.NotificationAttribute.SUBTITLE,
        Ancs.NotificationAttribute.MESSAGE, Ancs.NotificationAttribute.DATE,
        Ancs.NotificationAttribute.POSITIVE_ACTION_LABEL, Ancs.NotificationAttribute.NEGATIVE_ACTION_LABEL,
    )

    /** Feed each Notification Source GATT notification. */
    @Synchronized
    fun onSource(bytes: ByteArray) {
        val event = Ancs.parseSource(bytes)
        when (event.event) {
            Ancs.EventId.REMOVED -> {
                known.remove(event.uid)
                pending.removeAll { it.uid == event.uid }
                onRemoved(key(event.uid))
            }
            else -> {
                // Skip notifications that were already on the iPhone before we connected.
                if (event.preExisting) return
                known[event.uid] = event
                pending.addLast(event)
                pump()
            }
        }
    }

    /** Feed each Data Source GATT notification. */
    @Synchronized
    fun onData(bytes: ByteArray) {
        when (val response = assembler.feed(bytes) ?: return) {
            is Ancs.NotificationAttributes -> {
                val event = inFlight ?: return
                val appId = response.appIdentifier.orEmpty()
                if (appId.isNotEmpty() && appId !in appNames) {
                    awaitingAppName = event to response
                    assembler.expectAppAttributes()
                    writeControlPoint(Ancs.getAppAttributes(appId))
                    return
                }
                emit(event, response)
            }
            is Ancs.AppAttributes -> {
                appNames[response.appIdentifier] = response.displayName ?: response.appIdentifier
                awaitingAppName?.let { (event, attrs) -> emit(event, attrs) }
                awaitingAppName = null
            }
        }
    }

    /** Answer / decline a call, or the notification's own positive / negative action. */
    @Synchronized
    fun perform(key: String, actionId: String): Boolean {
        val uid = key.removePrefix(KEY_PREFIX).toLongOrNull() ?: return false
        if (uid !in known) return false
        val action = when (actionId) { POSITIVE -> Ancs.Action.POSITIVE; NEGATIVE -> Ancs.Action.NEGATIVE; else -> return false }
        writeControlPoint(Ancs.performAction(uid, action))
        return true
    }

    private fun emit(event: Ancs.SourceEvent, attrs: Ancs.NotificationAttributes) {
        inFlight = null
        val v = attrs.values
        val appId = attrs.appIdentifier.orEmpty()
        val subtitle = v[Ancs.NotificationAttribute.SUBTITLE].orEmpty()
        val actions = buildList {
            if (event.hasPositiveAction) add(NotificationActionInfo(POSITIVE, v[Ancs.NotificationAttribute.POSITIVE_ACTION_LABEL] ?: "OK"))
            if (event.hasNegativeAction) add(NotificationActionInfo(NEGATIVE, v[Ancs.NotificationAttribute.NEGATIVE_ACTION_LABEL] ?: "Dismiss"))
        }
        onNotification(
            MirroredNotification(
                key = key(event.uid),
                appId = appId,
                appName = appNames[appId] ?: appId,
                title = attrs.title.orEmpty(),
                text = listOf(subtitle, attrs.message.orEmpty()).filter { it.isNotEmpty() }.joinToString("\n"),
                postedAtEpochMs = attrs.date?.atZone(zone)?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis(),
                kind = when (event.category) {
                    Ancs.Category.INCOMING_CALL -> NotificationKind.CALL
                    Ancs.Category.MISSED_CALL, Ancs.Category.VOICEMAIL -> NotificationKind.MISSED_CALL
                    Ancs.Category.SOCIAL -> NotificationKind.MESSAGE
                    Ancs.Category.EMAIL -> NotificationKind.EMAIL
                    Ancs.Category.SCHEDULE -> NotificationKind.EVENT
                    else -> NotificationKind.OTHER
                },
                actions = actions,
                silent = event.silent,
            ),
        )
        pump()
    }

    private fun pump() {
        if (inFlight != null || awaitingAppName != null) return
        val next = pending.removeFirstOrNull() ?: return
        inFlight = next
        assembler.expectNotificationAttributes(requested.size)
        writeControlPoint(Ancs.getNotificationAttributes(next.uid, requested))
    }

    companion object {
        const val POSITIVE = "positive"
        const val NEGATIVE = "negative"
        private const val KEY_PREFIX = "ancs:"
        fun key(uid: Long) = "$KEY_PREFIX$uid"
    }
}
