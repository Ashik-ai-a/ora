package app.airbox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.airbox.android.platform.IncomingPrompt
import app.airbox.core.web.PairedDevice

/** Phones that paired once. They never scan again; "Always accept" skips the prompt. */
@Composable
fun PairedCard(devices: List<PairedDevice>, onAutoAccept: (PairedDevice, Boolean) -> Unit, onForget: (PairedDevice) -> Unit, onPairAnother: () -> Unit) {
    val c = LocalAirboxColors.current
    Column {
        Text("My devices", style = AirboxType.title3, color = c.label, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 16.dp)) {
            devices.forEachIndexed { i, device ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF5AC8FA), Color(0xFF007AFF)))),
                        contentAlignment = Alignment.Center) {
                        Text(initials(device.name), style = AirboxType.subhead, color = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(device.name, style = AirboxType.body, color = c.label)
                        Text(if (device.autoAccept) "Saves without asking" else "Asks before saving",
                            style = AirboxType.footnote, color = c.label2)
                    }
                    Switch(checked = device.autoAccept, onCheckedChange = { onAutoAccept(device, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.green))
                }
                Text("Forget this device", style = AirboxType.subhead, color = c.red,
                    modifier = Modifier.padding(start = 52.dp, bottom = 12.dp).clickable { onForget(device) })
                if (i < devices.size - 1) HorizontalDivider(color = c.separator)
            }
        }
        Text("Pair another iPhone", style = AirboxType.subhead, color = c.blue,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp).clickable(onClick = onPairAnother))
    }
}

/**
 * The AirDrop moment on Android when the app is open: who's sharing, what, and two big
 * buttons. (When it's closed, the same choice pops up as a notification.)
 */
@Composable
fun IncomingPromptDialog(prompt: IncomingPrompt, onAccept: (always: Boolean) -> Unit, onDecline: () -> Unit) {
    val c = LocalAirboxColors.current
    Dialog(onDismissRequest = {}) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            PeerBubble(name = prompt.from, status = "would like to share", online = true, phase = SendPhase.Idle)
            Text(prompt.summary, style = AirboxType.headline, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp))
            Text(sizeLabel(prompt.totalBytes), style = AirboxType.subhead, color = c.label2)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Decline", c.surface2, c.label, Modifier.weight(1f), onDecline)
                PillButton("Accept", c.blue, Color.White, Modifier.weight(1f)) { onAccept(false) }
            }
            Text("Always accept from ${prompt.from}", style = AirboxType.subhead, color = c.blue,
                modifier = Modifier.padding(top = 14.dp).clickable { onAccept(true) })
        }
    }
}

@Composable
private fun PillButton(label: String, background: Color, foreground: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(50.dp).clip(RoundedCornerShape(25.dp)).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, style = AirboxType.headline, color = foreground)
    }
}

fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}
