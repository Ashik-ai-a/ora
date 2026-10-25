package app.airbox.android.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.airbox.core.live.IslandItem
import app.airbox.core.live.IslandKind

/**
 * Notifications that work on every Android version from 7.0 (API 24):
 *  - channels on 8.0+,
 *  - Live Updates (status-bar chip, Android 16+) for transfers, falling back to a normal
 *    ongoing progress notification everywhere else.
 * Framework APIs only (no AndroidX) so this is type-checked against the real SDK in CI.
 */
object Notifications {
    const val CHANNEL_SERVICE = "airbox.service"
    const val CHANNEL_RECEIVED = "airbox.received"
    const val CHANNEL_PROGRESS = "airbox.progress"
    const val ID_SERVICE = 1
    const val ID_PROGRESS = 2
    private const val ID_RECEIVED_BASE = 100
    private const val ACCENT = 0xFF0A84FF.toInt()
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SERVICE, "Ready to receive", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Airbox can receive from your iPhone."
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_RECEIVED, "Received from iPhone", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Text and files that arrive from your iPhone."
                    lightColor = ACCENT
                },
                NotificationChannel(CHANNEL_PROGRESS, "Transfers in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of files moving between your phones."
                    setShowBadge(false)
                },
            ),
        )
    }

    private fun builder(context: Context, channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else @Suppress("DEPRECATION") Notification.Builder(context))
            .setSmallIcon(context.resources.getIdentifier("ic_stat_airbox", "drawable", context.packageName))
            .setColor(ACCENT)

    private fun openApp(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun service(context: Context, address: String?): Notification =
        builder(context, CHANNEL_SERVICE)
            .setContentTitle("Ready for your iPhone")
            .setContentText(address?.let { "On this Wi-Fi at $it" } ?: "Join a Wi-Fi network or turn on the hotspot")
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    fun textReceived(context: Context, text: String) {
        notify(context, ID_RECEIVED_BASE + (text.hashCode() and 0xff),
            builder(context, CHANNEL_RECEIVED)
                .setContentTitle("Copied from iPhone")
                .setContentText(text.take(120))
                .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build())
    }

    fun fileReceived(context: Context, name: String, open: PendingIntent?) {
        notify(context, ID_RECEIVED_BASE + (name.hashCode() and 0xff) + 256,
            builder(context, CHANNEL_RECEIVED)
                .setContentTitle("Received from iPhone")
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(open ?: openApp(context))
                .build())
    }

    /**
     * Shows [item] as Android's equivalent of the Dynamic Island. On Android 16+ a
     * ProgressStyle notification asking to be promoted becomes a status-bar chip (Live Update)
     * when the user allows it; older versions get a regular ongoing progress notification.
     */
    fun live(context: Context, item: IslandItem) {
        val b = builder(context, CHANNEL_PROGRESS)
            .setContentTitle(item.title)
            .setContentText(item.subtitle)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setCategory(if (item.kind == IslandKind.INCOMING_CALL) Notification.CATEGORY_CALL else Notification.CATEGORY_PROGRESS)
        val progress = item.progress
        val ongoing = progress == null || progress < 1.0
        b.setOngoing(ongoing).setAutoCancel(!ongoing)
        if (Build.VERSION.SDK_INT >= 36) {
            if (progress != null) {
                b.setStyle(
                    Notification.ProgressStyle()
                        .setStyledByProgress(true)
                        .setProgress((progress * 100).toInt())
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(ACCENT))),
                )
            }
            b.setShortCriticalText(item.compact)
            // Ask for promotion to a status-bar chip. The framework jar we type-check against has
            // no public setter; Android 16 reads this extra (AndroidX:
            // NotificationCompat.Builder#setRequestPromotedOngoing). Verify the key on the SDK.
            if (ongoing) b.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        } else if (progress != null && ongoing) {
            b.setProgress(100, (progress * 100).toInt(), false)
        }
        notify(context, ID_PROGRESS, b.build())
    }

    fun clearLive(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_PROGRESS)
    }

    /** Android 16: whether the user lets Airbox show Live Updates in the status bar. */
    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            (context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() ?: false)

    private fun notify(context: Context, id: Int, notification: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Android 13+: silently skipped without POST_NOTIFICATIONS; the UI asks for it up front.
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        nm.notify(id, notification)
    }
}
