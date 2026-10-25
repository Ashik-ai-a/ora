package app.airbox.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where a send is: drives the ring around the avatar exactly like AirDrop. */
sealed interface SendPhase {
    data object Idle : SendPhase
    data class Sending(val progress: Float) : SendPhase
    data class Done(val label: String) : SendPhase
    data class Failed(val label: String) : SendPhase
}

/**
 * The centrepiece: the peer as an avatar bubble with ripples behind it. A ring fills
 * around it while sending, turns green, and a ✓ springs in with a haptic tick.
 */
@Composable
fun PeerBubble(
    name: String,
    status: String,
    online: Boolean,
    phase: SendPhase,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalAirboxColors.current
    val haptics = LocalHapticFeedback.current
    val target = when (phase) {
        is SendPhase.Sending -> phase.progress
        is SendPhase.Done -> 1f
        else -> 0f
    }
    val progress by animateFloatAsState(target, tween(300), label = "ring")
    val done = phase is SendPhase.Done
    val pop by animateFloatAsState(if (done) 1.06f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "pop")
    val check = remember { Animatable(0f) }
    LaunchedEffect(done) {
        if (done) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            check.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        } else check.snapTo(0f)
    }
    val ringColor = if (done) c.green else c.blue

    Column(modifier.semantics { contentDescription = "$name, $status" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            if (phase is SendPhase.Idle) Ripples(online, c.blue)
            Canvas(Modifier.size(112.dp)) {
                val stroke = 4.dp.toPx()
                val inset = stroke / 2
                if (phase !is SendPhase.Idle) {
                    drawCircle(c.surface2, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                }
                drawArc(ringColor, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Box(
                Modifier.size(90.dp).scale(pop).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF5AC8FA), Color(0xFF007AFF))))
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials(name), style = AirboxType.avatar, color = Color.White)
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp).size(32.dp).scale(check.value)
                .clip(CircleShape).background(c.green), contentAlignment = Alignment.Center) {
                CheckMark(Color.White, check.value)
            }
        }
        Text(name, style = AirboxType.headline, color = c.label)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) c.green else c.orange))
            Spacer(Modifier.width(6.dp))
            Text(status, style = AirboxType.subhead.copy(fontWeight = if (done) AirboxType.headline.fontWeight else null),
                color = if (done) c.green else c.label2)
        }
    }
}

@Composable
private fun Ripples(slow: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "ripples")
    val period = if (slow) 5400 else 3200
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(Modifier.size(150.dp)) {
        for (i in 0 until 3) {
            val phase = (t + i / 3f) % 1f
            // From the avatar's edge (45dp) out to the edge of the box (75dp).
            val radius = 45.dp.toPx() + 30.dp.toPx() * phase
            drawCircle(color.copy(alpha = 0.32f * (1 - phase)), radius = radius, style = Stroke((10 * (1 - phase) + 1).dp.toPx()))
        }
    }
}

@Composable
private fun CheckMark(color: Color, draw: Float) {
    Canvas(Modifier.size(18.dp)) {
        val p1 = Offset(size.width * 0.12f, size.height * 0.52f)
        val p2 = Offset(size.width * 0.40f, size.height * 0.80f)
        val p3 = Offset(size.width * 0.90f, size.height * 0.22f)
        val first = (draw * 2).coerceIn(0f, 1f)
        val second = (draw * 2 - 1).coerceIn(0f, 1f)
        val stroke = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, p1, p1 + (p2 - p1) * first, stroke.width, StrokeCap.Round)
        if (second > 0) drawLine(color, p2, p2 + (p3 - p2) * second, stroke.width, StrokeCap.Round)
    }
}

/** Round action like the iOS share sheet: 62dp circle, icon in blue, label below. */
@Composable
fun RoundAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalAirboxColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f), label = "press")
    Column(modifier.clickable(interaction, indication = null, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(62.dp).scale(scale).clip(CircleShape).background(if (pressed) c.surface2 else c.surface),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.blue, modifier = Modifier.size(28.dp))
        }
        Text(label, style = AirboxType.caption, color = c.label2, textAlign = TextAlign.Center)
    }
}

@Composable
fun MiniRing(progress: Float, color: Color, size: Dp = 22.dp) {
    val c = LocalAirboxColors.current
    Canvas(Modifier.size(size)) {
        val s = 3.dp.toPx()
        drawCircle(c.surface2, style = Stroke(s), radius = this.size.minDimension / 2 - s / 2)
        drawArc(color, -90f, 360f * progress, false, topLeft = Offset(s / 2, s / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s), style = Stroke(s, cap = StrokeCap.Round))
    }
}

/** "Ana's iPhone 15" -> "AI", "iPhone" -> "IP". */
fun initials(name: String): String {
    val words = name.replace("'s", "").replace("’s", "").split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return "?"
    if (words.size == 1) return words[0].take(2).uppercase()
    val digits = words[1].takeWhile { it.isDigit() }
    return (words[0].take(1) + (digits.ifEmpty { words[1].take(1) })).take(2).uppercase()
}
