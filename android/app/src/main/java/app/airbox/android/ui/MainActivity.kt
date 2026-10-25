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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.airbox.android.platform.AirboxService
import app.airbox.android.platform.AirboxState
import app.airbox.android.platform.AirboxUiState
import app.airbox.android.platform.Clipboard
import app.airbox.android.platform.DeviceCompat
import app.airbox.android.platform.Qr
import app.airbox.android.platform.RecentTransfer
import app.airbox.android.platform.UriOutgoingFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AirboxService.start(this)
        setContent { AirboxTheme { Home() } }
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    val c = LocalAirboxColors.current
    val state by AirboxState.flow.collectAsStateWithLifecycle()
    var phase by remember { mutableStateOf<SendPhase>(SendPhase.Idle) }
    val scope = rememberCoroutineScope()

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

        item { ConnectCard(state, onNewCode = { AirboxService.start(context, AirboxService.ACTION_NEW_PAIRING) },
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

@Composable
private fun RecentList(items: List<RecentTransfer>) {
    val c = LocalAirboxColors.current
    Column {
        Text("Recent", style = AirboxType.title3, color = c.label, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 16.dp)) {
            items.take(8).forEachIndexed { i, item ->
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniRing(1f, c.green)
                    Text(item.name, style = AirboxType.body, color = c.label, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Text(if (item.incoming) "Received" else "Shared", style = AirboxType.subhead, color = c.label2)
                }
                if (i < minOf(items.size, 8) - 1) HorizontalDivider(color = c.separator)
            }
        }
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
