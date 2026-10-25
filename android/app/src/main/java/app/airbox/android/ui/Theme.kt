package app.airbox.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.airbox.android.R

/** Apple system colours, so both phones feel like one product. */
@Immutable
data class AirboxColors(
    val bg: Color, val surface: Color, val surface2: Color, val label: Color, val label2: Color, val label3: Color,
    val separator: Color, val blue: Color, val green: Color, val red: Color, val orange: Color,
)

private val Light = AirboxColors(
    bg = Color(0xFFF2F2F7), surface = Color.White, surface2 = Color(0xFFE9E9EF), label = Color.Black,
    label2 = Color(0x993C3C43), label3 = Color(0x4D3C3C43), separator = Color(0x2E3C3C43),
    blue = Color(0xFF007AFF), green = Color(0xFF34C759), red = Color(0xFFFF3B30), orange = Color(0xFFFF9500),
)
private val Dark = AirboxColors(
    bg = Color.Black, surface = Color(0xFF1C1C1E), surface2 = Color(0xFF2C2C2E), label = Color.White,
    label2 = Color(0x99EBEBF5), label3 = Color(0x4DEBEBF5), separator = Color(0x99545458),
    blue = Color(0xFF0A84FF), green = Color(0xFF30D158), red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A),
)

val LocalAirboxColors = staticCompositionLocalOf { Light }

/** Inter, bundled: the closest open match to SF Pro, identical on every Android phone. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** The iOS type scale (Large Title 34, Title 3 20, Headline 17 semibold, Body 17, Subhead 15, Footnote 13, Caption 12). */
object AirboxType {
    val largeTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.011.em)
    val title3 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.017).em)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.022).em)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.022).em)
    val subhead = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.015).em)
    val footnote = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.006).em)
    val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val avatar = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = 0.em)
}

@Composable
fun AirboxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    val material = if (dark) darkColorScheme(primary = c.blue, background = c.bg, surface = c.surface)
    else lightColorScheme(primary = c.blue, background = c.bg, surface = c.surface)
    CompositionLocalProvider(LocalAirboxColors provides c) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
