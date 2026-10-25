package app.airbox.android.platform

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import app.airbox.core.live.IslandAction
import app.airbox.core.live.IslandEvent
import app.airbox.core.live.IslandModel
import app.airbox.core.transfer.DirectoryReceiveStorage
import app.airbox.core.transfer.OutgoingFile
import app.airbox.core.web.FilePairedDeviceStore
import app.airbox.core.web.WebBridge
import java.io.File
import java.time.Duration

/**
 * Keeps Airbox reachable from the iPhone: runs the local web bridge (Safari page,
 * Shortcuts, the Airbox iPhone app) inside a foreground service with a visible
 * notification, which is what every Android version from 7.0 to 16 allows.
 */
class AirboxService : Service() {
    private var bridge: WebBridge? = null
    private var island: IslandModel? = null
    private val hotspot by lazy { Net.Hotspot(this) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AirboxState.attachRecentStore(File(filesDir, "recent.json"))
        Notifications.createChannels(this)
        goForeground(Net.localAddress(this))
        startBridge()
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NEW_PAIRING -> refreshPairing()
            ACTION_START_HOTSPOT -> hotspot.start(
                onStarted = { info ->
                    AirboxState.update { it.copy(hotspot = info) }
                    refreshPairing()
                },
                onFailed = { message -> AirboxState.update { it.copy(hotspot = null, lastText = message) } },
            )
            ACTION_STOP_HOTSPOT -> { hotspot.stop(); AirboxState.update { it.copy(hotspot = null) }; refreshPairing() }
            ACTION_SHARE_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { shareText(it) }
            ACTION_SHARE_FILES -> { shareFiles(pendingFiles); pendingFiles = emptyList() }
            ACTION_ACCEPT, ACTION_ACCEPT_ALWAYS -> intent.getStringExtra(EXTRA_TRANSFER)?.let { id ->
                bridge?.accept(id, always = intent.action == ACTION_ACCEPT_ALWAYS)
                resolvePrompt(intent.getStringExtra(EXTRA_PROMPT))
            }
            ACTION_DECLINE -> intent.getStringExtra(EXTRA_TRANSFER)?.let { id ->
                bridge?.decline(id)
                resolvePrompt(intent.getStringExtra(EXTRA_PROMPT))
            }
            ACTION_FORGET -> intent.getStringExtra(EXTRA_DEVICE)?.let { bridge?.forget(it); refreshPaired() }
            ACTION_AUTO_ACCEPT -> intent.getStringExtra(EXTRA_DEVICE)?.let {
                bridge?.setAutoAccept(it, intent.getBooleanExtra(EXTRA_ENABLED, false)); refreshPaired()
            }
            ACTION_STOP -> stopSelf()
        }
        // Restart after the system reclaims memory; brands with aggressive killers are
        // handled through DeviceCompat guidance in Settings.
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        bridge?.close()
        hotspot.stop()
        Ringer.stop()
        AirboxState.update { it.copy(running = false, pairingUrl = null) }
        super.onDestroy()
    }

    private fun goForeground(address: String?) {
        val notification = Notifications.service(this, address)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifications.ID_SERVICE, notification)
        }
    }

    private fun startBridge() {
        val inbox = File(filesDir, "inbox").apply { mkdirs() }
        val storage = DirectoryReceiveStorage(inbox.toPath()).also { it.purgeStale(Duration.ofDays(2)) }
        val name = deviceName()
        island = IslandModel(peerName = "iPhone")
        // Fixed port keeps an iOS Shortcut working across restarts; fall back if it's taken.
        val port = if (runCatching { java.net.ServerSocket(PORT).close() }.isSuccess) PORT else 0
        bridge = WebBridge(
            storage = storage,
            deviceName = name,
            port = port,
            onText = { text ->
                Clipboard.put(this, text)
                Notifications.textReceived(this, text)
                AirboxState.update { it.copy(lastText = text, iphoneSeen = true) }
                show(IslandEvent.ClipboardReceived(text))
            },
            pairedStore = FilePairedDeviceStore(File(filesDir, "paired.json").toPath()),
            onIncoming = { transfer -> addToPrompt(transfer) },
            onClipboardImage = { entry, savedAs ->
                val uri = SharedContent.publishReceived(this, File(inbox, savedAs), entry.mimeType)
                if (uri != null) Clipboard.putImage(this, uri)
                Notifications.textReceived(this, "Image copied — paste it anywhere")
                AirboxState.update { it.copy(iphoneSeen = true) }
            },
            onFile = { entry, savedAs ->
                val file = File(inbox, savedAs)
                // Photos and videos go to the Gallery / Photos app; other files to Downloads/Airbox.
                val uri = SharedContent.publishReceived(this, file, entry.mimeType)
                val open = uri?.let {
                    PendingIntent.getActivity(this, savedAs.hashCode(),
                        Intent(Intent.ACTION_VIEW).setDataAndType(it, entry.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        PendingIntent.FLAG_IMMUTABLE)
                }
                Notifications.fileReceived(this, savedAs, open)
                AirboxState.addRecent(RecentTransfer(savedAs, incoming = true, sizeBytes = entry.size,
                    atEpochMs = System.currentTimeMillis(), uri = uri?.toString()))
                AirboxState.update { it.copy(iphoneSeen = true) }
            },
            onAction = { action, target -> handleAction(action, target) },
        ).start()
        AirboxState.update { it.copy(running = true) }
        refreshPaired()
        refreshPairing()
    }

    private fun refreshPaired() {
        val devices = bridge?.pairedDevices().orEmpty()
        AirboxState.update { it.copy(paired = devices, iphoneSeen = it.iphoneSeen || devices.isNotEmpty()) }
    }

    /** Groups the photos of one share into a single AirDrop-style prompt. */
    @Synchronized
    private fun addToPrompt(t: WebBridge.PendingTransfer) {
        val promptId = t.batchId ?: t.id
        val existing = AirboxState.flow.value.prompts.firstOrNull { it.promptId == promptId }
        val prompt = existing?.copy(names = existing.names + t.fileName, totalBytes = existing.totalBytes + t.size,
            mimeTypes = existing.mimeTypes + t.mimeType)
            ?: IncomingPrompt(promptId, t.id, t.fromName, t.fromDeviceId, listOf(t.fileName), t.size, listOf(t.mimeType))
        AirboxState.update { s -> s.copy(prompts = s.prompts.filterNot { it.promptId == promptId } + prompt, iphoneSeen = true) }
        fun action(action: String, code: Int) = servicePendingIntent(action, code + promptId.hashCode()) {
            putExtra(EXTRA_TRANSFER, prompt.firstTransferId)
            putExtra(EXTRA_PROMPT, promptId)
        }
        val open = PendingIntent.getActivity(this, promptId.hashCode(),
            (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Notifications.incoming(this, promptId, prompt.from, prompt.summary,
            linkedMapOf("Accept" to action(ACTION_ACCEPT, 1), "Decline" to action(ACTION_DECLINE, 2),
                "Always accept" to action(ACTION_ACCEPT_ALWAYS, 3)), open)
    }

    private fun resolvePrompt(promptId: String?) {
        if (promptId == null) return
        Notifications.clearIncoming(this, promptId)
        AirboxState.update { s -> s.copy(prompts = s.prompts.filterNot { it.promptId == promptId }) }
        refreshPaired()
    }

    private fun servicePendingIntent(action: String, requestCode: Int, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(this, AirboxService::class.java).setAction(action).apply(extras)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, requestCode, intent, flags)
        else PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun refreshPairing() {
        val address = Net.localAddress(this)
        val url = address?.let { bridge?.pairingUrl(it, ttlMillis = 10 * 60_000) }
        AirboxState.update { it.copy(wifiAddress = address, pairingUrl = url) }
        goForeground(address)
    }

    /** Re-issue the QR code when Wi-Fi changes, since the address in it changes too. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshPairing()
            override fun onLost(network: Network) = refreshPairing()
        }
        if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun handleAction(action: IslandAction, target: String?) {
        when (action) {
            IslandAction.STOP_RING -> { Ringer.stop(); show(IslandEvent.RingStopped) }
            IslandAction.CANCEL -> Notifications.clearLive(this)
            // Media and call actions need the NotificationListener integration (see docs/COMPANION.md).
            else -> Unit
        }
    }

    private fun shareText(text: String) {
        bridge?.shareText(text)
        AirboxState.addRecent(RecentTransfer("Text", incoming = false, sizeBytes = text.length.toLong(), atEpochMs = System.currentTimeMillis()))
    }

    private fun shareFiles(files: List<OutgoingFile>) {
        files.forEach { file ->
            bridge?.shareFile(file)
            AirboxState.addRecent(RecentTransfer(file.name, incoming = false, sizeBytes = file.size, atEpochMs = System.currentTimeMillis()))
        }
    }

    private fun show(event: IslandEvent) {
        val model = island ?: return
        if (!model.apply(event)) return
        val top = model.visible.firstOrNull()
        if (top == null) Notifications.clearLive(this) else Notifications.live(this, top)
        // Mirror onto the iPhone's Dynamic Island through the Airbox iPhone app, if paired.
        top?.let { bridge?.publishIsland(it) }
    }

    private fun deviceName(): String =
        (if (Build.VERSION.SDK_INT >= 25) android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME) else null)
            ?: Build.MODEL ?: "Android"

    companion object {
        /** Fixed so an iOS Shortcut keeps working after a restart. */
        const val PORT = 47820
        const val ACTION_NEW_PAIRING = "app.airbox.NEW_PAIRING"
        const val ACTION_START_HOTSPOT = "app.airbox.START_HOTSPOT"
        const val ACTION_STOP_HOTSPOT = "app.airbox.STOP_HOTSPOT"
        const val ACTION_SHARE_TEXT = "app.airbox.SHARE_TEXT"
        const val ACTION_SHARE_FILES = "app.airbox.SHARE_FILES"
        const val ACTION_STOP = "app.airbox.STOP"
        const val ACTION_ACCEPT = "app.airbox.ACCEPT"
        const val ACTION_ACCEPT_ALWAYS = "app.airbox.ACCEPT_ALWAYS"
        const val ACTION_DECLINE = "app.airbox.DECLINE"
        const val ACTION_FORGET = "app.airbox.FORGET"
        const val ACTION_AUTO_ACCEPT = "app.airbox.AUTO_ACCEPT"
        const val EXTRA_TRANSFER = "transfer"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_TEXT = "text"

        /** Files handed over by the share sheet, consumed by the running service. */
        @Volatile var pendingFiles: List<OutgoingFile> = emptyList()

        fun start(context: Context, action: String? = null, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, AirboxService::class.java).setAction(action).apply(configure)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
