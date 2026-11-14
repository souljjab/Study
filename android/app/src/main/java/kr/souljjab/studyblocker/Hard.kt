package kr.souljjab.studyblocker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 60 → "1시간", 90 → "1시간 30분", 30 → "30분" */
internal fun durationText(m: Int): String = when {
    m < 60 -> "${m}분"
    m % 60 == 0 -> "${m / 60}시간"
    else -> "${m / 60}시간 ${m % 60}분"
}

internal fun hardAppsText(names: List<String>): String =
    if (names.isEmpty()) "없음 (전화만 가능)" else names.joinToString(", ")

/** 집중 화면의 빡집중 설정 카드: 허용 앱(최대 3개)과 제한 시간을 고르고 시작한다. */
@Composable
internal fun HardSetupCard(
    appNames: List<String>,
    preset: Int,
    custom: String,
    accessibilityOn: Boolean,
    pal: Palette,
    onPick: () -> Unit,
    onPreset: (Int) -> Unit,
    onCustom: (String) -> Unit,
    onStart: () -> Unit,
) {
    val minutes = if (preset > 0) preset else custom.toIntOrNull() ?: 0
    val valid = minutes in 1..HardMode.MAX_MINUTES
    Column(
        Modifier
            .fillMaxWidth()
            .border(3.dp, pal.fg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("빡집중 모드", color = pal.fg, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(
            "고른 앱 3개 말고는 아무 앱도 쓸 수 없어요. 시작하면 끝날 때까지 절대 해제할 수 없고, 긴급 잠금 해제도 안 돼요.",
            color = pal.fg,
            fontSize = 14.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("쓸 수 있는 앱 ${appNames.size}/${HardMode.MAX_APPS}", pal)
                Text(hardAppsText(appNames), color = pal.fg, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .border(2.dp, pal.fg)
                    .clickable(onClick = onPick)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("앱 고르기", color = pal.fg, fontWeight = FontWeight.Black)
            }
        }
        Label("제한 시간", pal)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 120, -1).forEach { p ->
                val sel = p == preset
                Box(
                    Modifier
                        .weight(1f)
                        .border(2.dp, pal.fg)
                        .background(if (sel) pal.fg else Color.Transparent)
                        .clickable { onPreset(p) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (p > 0) durationText(p) else "직접", color = if (sel) pal.bg else pal.fg, fontWeight = FontWeight.Black)
                }
            }
        }
        if (preset < 0) {
            OutlinedTextField(
                value = custom,
                onValueChange = { v -> onCustom(v.filter(Char::isDigit).take(3)) },
                label = { Text("분 (1~720, 최대 12시간)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RectangleShape,
                colors = sharpFieldColors(pal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!accessibilityOn) {
            Text("접근성 권한을 켜야 시작할 수 있어요. 오른쪽 위 [권한]에서 켜 주세요.", color = pal.fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        SharpButton(
            if (valid) "빡집중 ${durationText(minutes)} 시작" else "빡집중 시작",
            pal,
            enabled = valid && accessibilityOn,
            onClick = onStart,
        )
    }
}

/** 빡집중 진행 중: 쓸 수 있는 앱만 보여 준다. 바꾸거나 멈추는 버튼은 없다. */
@Composable
internal fun HardActiveCard(appNames: List<String>, pal: Palette) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("쓸 수 있는 앱", pal)
        if (appNames.isEmpty()) {
            Text("없음", color = pal.fg, fontSize = 18.sp, fontWeight = FontWeight.Black)
        } else {
            appNames.forEach { Text(it, color = pal.fg, fontSize = 18.sp, fontWeight = FontWeight.Black) }
        }
        Text("전화는 언제든 쓸 수 있어요. 설정 앱과 앱 삭제는 막혀 있어요.", color = pal.muted, fontSize = 13.sp)
    }
}

/** 허용 앱을 최대 3개 고르는 창. */
@Composable
internal fun HardAppPicker(apps: List<AppItem>, selected: Set<String>, pal: Palette, onSave: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var sel by remember { mutableStateOf(selected.filter { p -> apps.any { it.pkg == p } }.toSet()) }
    val sorted = remember(apps) { apps.sortedWith(compareBy<AppItem>({ it.pkg !in selected }, { it.label.lowercase() })) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = pal.bg,
        title = { Text("쓸 수 있는 앱 ${sel.size}/${HardMode.MAX_APPS}", color = pal.fg, fontWeight = FontWeight.Black) },
        text = {
            if (apps.isEmpty()) {
                Text("앱 목록 불러오는 중…", color = pal.muted)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(sorted, key = { it.pkg }) { a ->
                        val on = a.pkg in sel
                        val full = !on && sel.size >= HardMode.MAX_APPS
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .alpha(if (full) 0.35f else 1f)
                                .clickable(enabled = !full) { sel = if (on) sel - a.pkg else sel + a.pkg }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(a.icon, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(a.label, color = pal.fg, fontWeight = if (on) FontWeight.Black else FontWeight.Normal, modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .border(2.dp, pal.fg)
                                    .background(if (on) pal.fg else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) Text("✓", color = pal.bg, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(sel) }, shape = RectangleShape) { Text("저장", color = pal.fg, fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = RectangleShape) { Text("취소", color = pal.fg) } },
    )
}

/** 시작 전 마지막 확인. 되돌릴 수 없다는 점과 끝나는 시각을 분명히 보여 준다. */
@Composable
internal fun HardConfirmDialog(minutes: Int, appNames: List<String>, pal: Palette, onStart: () -> Unit, onDismiss: () -> Unit) {
    val end = System.currentTimeMillis() + minutes * 60_000L
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = pal.bg,
        title = { Text("빡집중을 시작할까요?", color = pal.fg, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${durationText(minutes)} 동안, ${hhmm(end)}까지", color = pal.fg, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("쓸 수 있는 앱: ${hardAppsText(appNames)}", color = pal.fg, fontSize = 14.sp)
                Text(
                    "시작하면 끝날 때까지 어떤 방법으로도 해제할 수 없어요. 긴급 잠금 해제도 안 되고, 설정 앱도 열 수 없어요. 전화는 쓸 수 있어요.",
                    color = pal.fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onStart, shape = RectangleShape) { Text("시작", color = pal.fg, fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = RectangleShape) { Text("취소", color = pal.fg) } },
    )
}
