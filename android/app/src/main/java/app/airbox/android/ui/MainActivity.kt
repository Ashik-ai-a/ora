package app.airbox.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.airbox.android.platform.AirboxService
import app.airbox.android.platform.AirboxState
import app.airbox.android.platform.AirboxUiState
import app.airbox.android.platform.Clipboard
import app.airbox.android.platform.DeviceCompat
import app.airbox.android.platform.Qr
import app.airbox.android.platform.RecentTransfer
import app.airbox.android.platform.TextMessage
import app.airbox.android.platform.TimeLabel
import app.airbox.android.platform.UriOutgoingFile
import app.airbox.android.ui.terminal.Fn
import app.airbox.android.ui.terminal.IntradayScreen
import app.airbox.android.ui.terminal.MomentumScreen
import app.airbox.android.ui.terminal.MonitorScreen
import app.airbox.android.ui.terminal.ProofScreen
import app.airbox.android.ui.terminal.RiskScreen
import app.airbox.android.ui.terminal.TerminalChrome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AirboxService.start(this)
        TimeLabel.is24h = android.text.format.DateFormat.is24HourFormat(this)
        app.airbox.android.platform.MarketsStore.attach(applicationContext)
        setContent { AirboxTheme { AppShell() } }
    }
}

/**
 * The terminal (F1-F5) with Airbox sharing as F6. A share arriving from the iPhone jumps to
 * SHARE, where the Accept / Decline prompt lives.
 */
@Composable
private fun AppShell() {
    val state by AirboxState.flow.collectAsStateWithLifecycle()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Fn.MON) }
    LaunchedEffect(state.prompts.size) { if (state.prompts.isNotEmpty()) tab = Fn.SHARE }
    TerminalChrome(tab, { tab = it }) {
        when (tab) {
            Fn.MON -> MonitorScreen()
            Fn.MOM -> MomentumScreen()
            Fn.ORB -> IntradayScreen()
            Fn.PROOF -> ProofScreen()
            Fn.RISK -> RiskScreen()
            Fn.SHARE -> Home()
        }
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    val c = LocalAirboxColors.current
    val state by AirboxState.flow.collectAsStateWithLifecycle()
    var phase by remember { mutableStateOf<SendPhase>(SendPhase.Idle) }
    var pairingAnother by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A newly paired phone closes the "pair another" QR card.
    LaunchedEffect(state.paired.size) { pairingAnother = false }


    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AirboxService.start(context, AirboxService.ACTION_START_HOTSPOT)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Making something available to the iPhone: the ring runs round, then ✓.
    fun shared(label: String) = scope.launch {
        for (i in 1..10) { phase = SendPhase.Sending(i / 10f); delay(28) }
        phase = SendPhase.Done(label)
        delay(2400)
        phase = SendPhase.Idle
    }

    state.prompts.firstOrNull()?.let { prompt ->
        IncomingPromptDialog(
            prompt,
            onAccept = { always ->
                AirboxService.start(context, if (always) AirboxService.ACTION_ACCEPT_ALWAYS else AirboxService.ACTION_ACCEPT) {
                    putExtra(AirboxService.EXTRA_TRANSFER, prompt.firstTransferId); putExtra(AirboxService.EXTRA_PROMPT, prompt.promptId)
                }
                shared(if (prompt.names.size == 1) "Saved to Photos" else "${prompt.names.size} saved to Photos")
            },
            onDecline = {
                AirboxService.start(context, AirboxService.ACTION_DECLINE) {
                    putExtra(AirboxService.EXTRA_TRANSFER, prompt.firstTransferId); putExtra(AirboxService.EXTRA_PROMPT, prompt.promptId)
                }
            },
        )
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        AirboxService.pendingFiles = uris.map { UriOutgoingFile(context.contentResolver, it, null) }
        AirboxService.start(context, AirboxService.ACTION_SHARE_FILES)
        shared(if (uris.size == 1) "Ready on iPhone" else "${uris.size} items ready on iPhone")
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        AirboxService.pendingFiles = uris.map { UriOutgoingFile(context.contentResolver, it, null) }
        AirboxService.start(context, AirboxService.ACTION_SHARE_FILES)
        shared(if (uris.size == 1) "Ready on iPhone" else "${uris.size} items ready on iPhone")
    }

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg).safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Text("Airbox", style = AirboxType.largeTitle, color = c.label, modifier = Modifier.padding(top = 8.dp)) }

        item {
            PeerBubble(
                name = if (state.iphoneSeen) "iPhone" else "Your iPhone",
                status = when {
                    phase is SendPhase.Done -> (phase as SendPhase.Done).label
                    !state.running -> "Starting…"
                    state.wifiAddress == null -> "Join Wi-Fi or turn on the hotspot"
                    state.iphoneSeen -> "Connected"
                    else -> "Scan the code below with your iPhone"
                },
                online = state.wifiAddress != null,
                phase = phase,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundAction(Icons.Outlined.ContentPaste, "Send clipboard") {
                    val text = Clipboard.read(context)
                    if (!text.isNullOrBlank()) {
                        AirboxService.start(context, AirboxService.ACTION_SHARE_TEXT) { putExtra(AirboxService.EXTRA_TEXT, text) }
                        shared("Copy it on iPhone")
                    } else phase = SendPhase.Failed("The clipboard is empty")
                }
                RoundAction(Icons.Outlined.PhotoLibrary, "Photos") {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                RoundAction(Icons.Outlined.Description, "Files") { pickFiles.launch(arrayOf("*/*")) }
            }
        }

        item {
            MessagesCard(
                state.messages,
                onSend = { text ->
                    AirboxService.start(context, AirboxService.ACTION_SHARE_TEXT) { putExtra(AirboxService.EXTRA_TEXT, text) }
                    shared("Ready on iPhone")
                },
                onClear = { AirboxState.clearMessages() },
            )
        }

        // Paired once = never scan again. The QR code only comes back to add another iPhone.
        if (state.paired.isNotEmpty() && !pairingAnother) item {
            PairedCard(
                state.paired,
                onAutoAccept = { d, on -> AirboxService.start(context, AirboxService.ACTION_AUTO_ACCEPT) {
                    putExtra(AirboxService.EXTRA_DEVICE, d.id); putExtra(AirboxService.EXTRA_ENABLED, on) } },
                onForget = { d -> AirboxService.start(context, AirboxService.ACTION_FORGET) { putExtra(AirboxService.EXTRA_DEVICE, d.id) } },
                onPairAnother = { pairingAnother = true; AirboxService.start(context, AirboxService.ACTION_NEW_PAIRING) },
            )
        } else item { ConnectCard(state, onNewCode = { AirboxService.start(context, AirboxService.ACTION_NEW_PAIRING) },
            onHotspot = { on ->
                when {
                    !on -> AirboxService.start(context, AirboxService.ACTION_STOP_HOTSPOT)
                    Build.VERSION.SDK_INT >= 33 -> nearby.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                    else -> nearby.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }) }

        if (state.recent.isNotEmpty()) item { RecentList(state.recent) }

        item { Reliability() }

        item {
            Text("Sent straight over your Wi-Fi. Nothing goes through the internet.", style = AirboxType.footnote,
                color = c.label2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }
    }
}

/** The QR code the iPhone camera scans. With the hotspot on, the Wi-Fi code comes first. */
@Composable
private fun ConnectCard(state: AirboxUiState, onNewCode: () -> Unit, onHotspot: (Boolean) -> Unit) {
    val c = LocalAirboxColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val hotspot = state.hotspot
        val showWifiFirst = hotspot != null && !state.iphoneSeen
        var step by remember(hotspot) { mutableStateOf(if (showWifiFirst) 0 else 1) }
        val code = if (step == 0 && hotspot != null) hotspot.wifiQr else state.pairingUrl
        Text(
            if (step == 0) "1. Join this phone’s Wi-Fi" else if (hotspot != null) "2. Open Airbox on your iPhone" else "Connect your iPhone",
            style = AirboxType.headline, color = c.label,
        )
        Text(
            if (step == 0) "Point the iPhone camera at the code and tap Join."
            else "Point the iPhone camera at the code. No app needed.",
            style = AirboxType.subhead, color = c.label2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        AnimatedContent(code, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() }, label = "qr") { value ->
            if (value == null) {
                Box(Modifier.size(220.dp).clip(RoundedCornerShape(18.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                    Text("Waiting for Wi-Fi…", style = AirboxType.subhead, color = c.label2)
                }
            } else {
                val bitmap = remember(value) { Qr.bitmap(value, 600).asImageBitmap() }
                Image(bitmap, contentDescription = "QR code", modifier = Modifier.size(220.dp).clip(RoundedCornerShape(18.dp)))
            }
        }
        AnimatedVisibility(step == 0) {
            Text("Then tap Next", style = AirboxType.subhead, color = c.blue,
                modifier = Modifier.padding(top = 14.dp).clickable { step = 1 })
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = c.separator)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("No shared Wi-Fi", style = AirboxType.body, color = c.label)
                Text("Start a private hotspot for your iPhone", style = AirboxType.footnote, color = c.label2)
            }
            Switch(checked = hotspot != null, onCheckedChange = onHotspot,
                colors = SwitchDefaults.colors(checkedTrackColor = c.green))
        }
        Text("New code", style = AirboxType.subhead, color = c.blue, modifier = Modifier.padding(top = 12.dp).clickable(onClick = onNewCode))
    }
}

/**
 * Text both ways, like iMessage: iPhone on the left in grey, this phone on the right in
 * blue. Tap a bubble to copy it. The iPhone sees what you send in Airbox (Safari or app).
 */
@Composable
private fun MessagesCard(messages: List<TextMessage>, onSend: (String) -> Unit, onClear: () -> Unit) {
    val c = LocalAirboxColors.current
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Messages", style = AirboxType.title3, color = c.label, modifier = Modifier.weight(1f))
            if (messages.isNotEmpty()) Text("Clear", style = AirboxType.subhead, color = c.blue, modifier = Modifier.clickable(onClick = onClear))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (messages.isEmpty()) {
                Text("Type below to send text to your iPhone. Text you send from the iPhone shows up here too, and is already on this phone’s clipboard.",
                    style = AirboxType.subhead, color = c.label2, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
            }
            val shown = if (showAll) messages else messages.takeLast(8)
            if (!showAll && messages.size > shown.size) {
                Text("Show earlier", style = AirboxType.footnote, color = c.blue,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable { showAll = true }.padding(4.dp))
            }
            shown.forEach { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.incoming) Alignment.Start else Alignment.End) {
                    Text(
                        m.text, style = AirboxType.body, maxLines = 12,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = if (m.incoming) c.label else androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier
                            .fillMaxWidth(0.8f).wrapContentWidth(if (m.incoming) Alignment.Start else Alignment.End)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (m.incoming) c.surface2 else c.blue)
                            .clickable {
                                app.airbox.android.platform.Clipboard.put(context, m.text)
                                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Text("${if (m.incoming) "iPhone" else "You"} · ${TimeLabel.of(m.atEpochMs)}", style = AirboxType.caption, color = c.label2,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (draft.isEmpty()) Text("Message iPhone", style = AirboxType.body, color = c.label3)
                    androidx.compose.foundation.text.BasicTextField(
                        value = draft, onValueChange = { draft = it.take(256 * 1024) },
                        textStyle = AirboxType.body.copy(color = c.label), maxLines = 6,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.blue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val canSend = draft.isNotBlank()
                Box(
                    Modifier.padding(start = 8.dp, bottom = 3.dp).size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (canSend) c.blue else c.surface2)
                        .clickable(enabled = canSend) { onSend(draft.trimEnd()); draft = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.ArrowUpward, contentDescription = "Send",
                        tint = if (canSend) androidx.compose.ui.graphics.Color.White else c.label3, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private enum class RecentFilter(val label: String) { ALL("All"), RECEIVED("Received"), SENT("Sent") }

/** Newest first, filterable, with when each item moved in small text under its name. */
@Composable
private fun RecentList(items: List<RecentTransfer>) {
    val c = LocalAirboxColors.current
    val context = LocalContext.current
    var filter by remember { mutableStateOf(RecentFilter.ALL) }
    var showAll by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<RecentTransfer?>(null) }
    val shown = items
        .filter { when (filter) { RecentFilter.ALL -> true; RecentFilter.RECEIVED -> it.incoming; RecentFilter.SENT -> !it.incoming } }
        .sortedByDescending { it.atEpochMs }
    Column {
        Text("Recent", style = AirboxType.title3, color = c.label, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        // iOS-style segmented control
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(9.dp)).background(c.surface2).padding(2.dp)) {
            RecentFilter.entries.forEach { f ->
                val selected = f == filter
                Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (selected) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { filter = f; showAll = false }, contentAlignment = Alignment.Center) {
                    Text(f.label, style = AirboxType.subhead.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null),
                        color = c.label)
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)) {
            if (shown.isEmpty()) {
                Text(if (filter == RecentFilter.SENT) "Nothing sent yet" else "Nothing received yet",
                    style = AirboxType.subhead, color = c.label2, modifier = Modifier.padding(16.dp))
            }
            val visible = if (showAll) shown else shown.take(10)
            visible.forEachIndexed { i, item ->
                // key: a deleted row's swipe offset must not carry over to the row that moves up.
                androidx.compose.runtime.key(item.key) {
                    SwipeToDeleteRow(onDelete = {
                        if (item.canDeleteFile) confirm = item else AirboxState.removeRecent(item)
                    }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            MiniRing(1f, if (item.incoming) c.green else c.blue)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.name, style = AirboxType.body, color = c.label, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("${if (item.incoming) "Received" else "Sent"} · ${TimeLabel.of(item.atEpochMs)} · ${sizeLabel(item.sizeBytes)}",
                                    style = AirboxType.footnote, color = c.label2, maxLines = 1)
                            }
                        }
                    }
                }
                if (i < visible.size - 1) HorizontalDivider(color = c.separator, modifier = Modifier.padding(start = 50.dp))
            }
        }
        if (!showAll && shown.size > 10) {
            Text("Show all ${shown.size}", style = AirboxType.subhead, color = c.blue,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp).clickable { showAll = true })
        }
        if (shown.isNotEmpty()) {
            Text("Swipe left on an item to delete it.", style = AirboxType.footnote, color = c.label2,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }
    }

    confirm?.let { item ->
        DeleteSheet(
            item,
            onDeleteFile = {
                if (!AirboxState.deleteRecent(context, item)) {
                    android.widget.Toast.makeText(context, "Couldn’t delete it here. Delete it in Photos or Files instead.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                confirm = null
            },
            onRemoveFromList = { AirboxState.removeRecent(item); confirm = null },
            onCancel = { confirm = null },
        )
    }
}

/**
 * iOS-style swipe: drag left to reveal a red Delete button; let go past half of it to keep
 * it open, or swipe all the way to delete straight away. Tapping elsewhere closes it.
 */
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalAirboxColors.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val revealPx = with(density) { 88.dp.toPx() }
    var offset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var width by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    fun settle(target: Float) = scope.launch {
        androidx.compose.animation.core.animate(offset, target,
            animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f)) { v, _ -> offset = v }
    }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        Box(
            Modifier.matchParentSize().background(c.red)
                .clickable(enabled = offset < -1f) { settle(0f); onDelete() },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text("Delete", style = AirboxType.headline, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.padding(end = 20.dp))
        }
        Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(offset.toInt(), 0) }
                .background(c.surface)
                .draggable(
                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                        offset = (offset + delta).coerceIn(-width.toFloat(), 0f)
                    },
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        when {
                            offset < -width * 0.6f -> { settle(0f); onDelete() }
                            offset < -revealPx / 2 || velocity < -1200f -> settle(-revealPx)
                            else -> settle(0f)
                        }
                    },
                ),
        ) { content() }
    }
}

/** iOS-style action sheet: delete the file too, or only tidy the list. */
@Composable
private fun DeleteSheet(item: RecentTransfer, onDeleteFile: () -> Unit, onRemoveFromList: () -> Unit, onCancel: () -> Unit) {
    val c = LocalAirboxColors.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)) {
                Text("Delete “${item.name}”?", style = AirboxType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = c.label2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 16.dp, end = 16.dp))
                Text("It will also be removed from Photos or Downloads on this phone.", style = AirboxType.footnote, color = c.label2,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 14.dp))
                HorizontalDivider(color = c.separator)
                SheetButton("Delete", c.red, onDeleteFile)
                HorizontalDivider(color = c.separator)
                SheetButton("Remove from list only", c.blue, onRemoveFromList)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)) {
                SheetButton("Cancel", c.blue, onCancel, bold = true)
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit, bold: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = if (bold) AirboxType.headline else AirboxType.body.copy(fontSize = 20.sp), color = color)
    }
}


/** One honest row per thing that can stop Airbox receiving in the background on this phone. */
@Composable
private fun Reliability() {
    val context = LocalContext.current
    val c = LocalAirboxColors.current
    val battery = DeviceCompat.isIgnoringBatteryOptimizations(context)
    val brandFix = DeviceCompat.brandFix()
    Column {
        Text("Stay ready in the background", style = AirboxType.title3, color = c.label, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 16.dp)) {
            SettingRow("Battery optimisation", if (battery) "Off for Airbox" else "Android may pause Airbox", done = battery) {
                context.startActivity(DeviceCompat.batteryOptimizationIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (brandFix != null) {
                HorizontalDivider(color = c.separator)
                SettingRow(brandFix.title, brandFix.steps, done = false) {
                    context.startActivity(DeviceCompat.resolve(context, brandFix))
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, detail: String, done: Boolean, onClick: () -> Unit) {
    val c = LocalAirboxColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AirboxType.body, color = c.label)
            Text(detail, style = AirboxType.footnote, color = c.label2)
        }
        Text(if (done) "✓" else "Fix", style = AirboxType.headline, color = if (done) c.green else c.blue, modifier = Modifier.padding(start = 12.dp))
    }
}
