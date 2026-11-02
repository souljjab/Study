package kr.souljjab.studyblocker

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** 화면 상태. 상태마다 배경색이 바뀐다. */
enum class Mode { OFF, FOCUS, MANUAL, SCHEDULE }

data class Palette(
    val bg: Color,
    val fg: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val lightBg: Boolean,
)

val Navy = Color(0xFF0A1A33)
val Paper = Color(0xFFF2EFE8)
val Ink = Color(0xFF121212)
val Signal = Color(0xFF2FE39A)
val Amber = Color(0xFFF2A900)
val Pine = Color(0xFF0C2E26)

fun palette(mode: Mode, dark: Boolean): Palette = when (mode) {
    Mode.FOCUS -> Palette(Navy, Color.White, Color(0xFF8FA3C0), Signal, Ink, false)
    Mode.MANUAL -> Palette(Amber, Ink, Color(0xFF5C4300), Ink, Amber, true)
    Mode.SCHEDULE -> Palette(Pine, Color.White, Color(0xFF8DB8AA), Signal, Ink, false)
    Mode.OFF -> if (dark) {
        Palette(Ink, Paper, Color(0xFF8A8A8A), Paper, Ink, false)
    } else {
        Palette(Paper, Ink, Color(0xFF6E6E6E), Ink, Paper, true)
    }
}

/** 모든 모서리를 직각으로. */
private val Sharp = RoundedCornerShape(0.dp)

@Composable
fun StudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        shapes = Shapes(Sharp, Sharp, Sharp, Sharp, Sharp),
        content = content,
    )
}

@Composable
fun SystemBars(lightBg: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBg
            isAppearanceLightNavigationBars = lightBg
        }
    }
}

@Composable
fun Label(text: String, pal: Palette) {
    Text(
        text.uppercase(),
        color = pal.muted,
        fontSize = 11.sp,
        letterSpacing = 3.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun SharpButton(
    text: String,
    pal: Palette,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.35f)
            .background(if (filled) pal.accent else Color.Transparent)
            .border(2.dp, if (filled) pal.accent else pal.fg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) pal.onAccent else pal.fg,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            letterSpacing = 1.sp,
        )
    }
}

/** 알약 모양 스위치 대신 쓰는 사각 토글. */
@Composable
fun SquareToggle(on: Boolean, pal: Palette, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .border(2.dp, pal.fg)
            .background(if (on) pal.fg else Color.Transparent)
            .clickable(enabled = enabled) { onChange(!on) },
        contentAlignment = Alignment.Center,
    ) {
        if (on) Text("✓", color = pal.bg, fontWeight = FontWeight.Black)
    }
}

/** 원형 링 대신 쓰는 직사각형 진행 막대. */
@Composable
fun Bar(fraction: Float, pal: Palette) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .border(2.dp, pal.fg),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(pal.accent),
        )
    }
}

@Composable
fun sharpFieldColors(pal: Palette): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = pal.fg,
    unfocusedTextColor = pal.fg,
    focusedBorderColor = pal.fg,
    unfocusedBorderColor = pal.fg,
    focusedLabelColor = pal.fg,
    unfocusedLabelColor = pal.muted,
    focusedPlaceholderColor = pal.muted,
    unfocusedPlaceholderColor = pal.muted,
    cursorColor = pal.fg,
)

fun fmt(sec: Long): String {
    val s = maxOf(0L, sec)
    val h = s / 3600
    val m = s / 60 % 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
}
