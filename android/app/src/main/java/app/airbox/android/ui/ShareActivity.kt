package app.airbox.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.airbox.android.platform.AirboxService
import app.airbox.android.platform.SharedContent
import kotlinx.coroutines.delay

/**
 * "Send to iPhone" from any app's share sheet: a bottom sheet with the iPhone bubble.
 * Tap it, the ring runs, ✓ Sent, and the sheet closes on its own, like AirDrop.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = SharedContent.parse(this, intent)
        if (payload == null) { finish(); return }
        AirboxService.start(this)
        setContent {
            AirboxTheme {
                ShareSheet(
                    summary = when (payload) {
                        is SharedContent.Payload.Text -> "Text"
                        is SharedContent.Payload.Files -> if (payload.files.size == 1) payload.files[0].name else "${payload.files.size} items"
                    },
                    send = {
                        when (payload) {
                            is SharedContent.Payload.Text -> AirboxService.start(this, AirboxService.ACTION_SHARE_TEXT) {
                                putExtra(AirboxService.EXTRA_TEXT, payload.text)
                            }
                            is SharedContent.Payload.Files -> {
                                AirboxService.pendingFiles = payload.files
                                AirboxService.start(this, AirboxService.ACTION_SHARE_FILES)
                            }
                        }
                    },
                    close = { finish() },
                )
            }
        }
    }
}

@Composable
private fun ShareSheet(summary: String, send: () -> Unit, close: () -> Unit) {
    val c = LocalAirboxColors.current
    var phase by remember { mutableStateOf<SendPhase>(SendPhase.Idle) }
    var tapped by remember { mutableStateOf(false) }
    LaunchedEffect(tapped) {
        if (!tapped) return@LaunchedEffect
        send()
        for (i in 1..12) { phase = SendPhase.Sending(i / 12f); delay(30) }
        phase = SendPhase.Done("Ready on iPhone")
        delay(1500)
        close()
    }
    Column(Modifier.fillMaxSize()) {
        // Only the dimmed area above the sheet dismisses it; taps inside the sheet stay there.
        Box(Modifier.weight(1f).fillMaxWidth().clickable(onClick = close))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.bg)
                .navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(bottom = 16.dp).clip(RoundedCornerShape(3.dp)).background(c.label3).padding(horizontal = 18.dp, vertical = 2.dp))
            Text(summary, style = AirboxType.headline, color = c.label, maxLines = 1)
            Text("Tap your iPhone to send", style = AirboxType.subhead, color = c.label2, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            PeerBubble(
                name = "iPhone",
                status = when (phase) {
                    is SendPhase.Sending -> "Sending…"
                    is SendPhase.Done -> (phase as SendPhase.Done).label
                    else -> "Ready"
                },
                online = true,
                phase = phase,
                onClick = { tapped = true },
            )
        }
    }
}
