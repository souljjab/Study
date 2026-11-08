package kr.souljjab.studyblocker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/** 긴급 해제 중일 때 맨 위에 뜨는 띠. */
@Composable
internal fun UnlockBanner(leftMs: Long, reason: String, pal: Palette, onEnd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(3.dp, pal.fg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("긴급 잠금 해제 중", pal)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(leftMs / 1000), color = pal.fg, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 32.sp)
            Text("  뒤 다시 차단", color = pal.fg, fontWeight = FontWeight.Bold)
        }
        Text("사유: $reason", color = pal.muted, fontSize = 13.sp)
        SharpButton("지금 다시 차단", pal, onClick = onEnd)
    }
}

/** 사유와 시간을 받는 긴급 해제 창. onSubmit이 오류 문구를 돌려주면 그대로 보여 준다. */
@Composable
internal fun UnlockDialog(pal: Palette, onSubmit: (String, Int) -> String?, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(10) }
    var custom by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val len = Unlock.normalize(reason).let { it.codePointCount(0, it.length) }
    val min = if (minutes > 0) minutes else custom.toIntOrNull() ?: 0
    val ok = len >= Unlock.MIN_REASON && min in 1..Unlock.MAX_MINUTES
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = pal.bg,
        title = { Text("긴급 잠금 해제", color = pal.fg, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "정말 필요한 경우에만 쓰세요. 사유와 해제 시간은 기록에 남고, 시간이 지나면 저절로 다시 차단돼요.",
                    color = pal.fg,
                    fontSize = 14.sp,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(300); error = null },
                    label = { Text("정당한 사유") },
                    placeholder = { Text("예: 강의 자료가 유튜브에만 올라와 있어요") },
                    minLines = 3,
                    shape = RectangleShape,
                    colors = sharpFieldColors(pal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${len}자 · 10자 이상",
                    color = if (len >= Unlock.MIN_REASON) pal.muted else pal.fg,
                    fontWeight = if (len >= Unlock.MIN_REASON) FontWeight.Normal else FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Label("해제할 시간", pal)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 30).forEach { m ->
                        val sel = m == minutes
                        Box(
                            Modifier
                                .weight(1f)
                                .border(2.dp, pal.fg)
                                .background(if (sel) pal.fg else Color.Transparent)
                                .clickable { minutes = m; custom = ""; error = null }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${m}분", color = if (sel) pal.bg else pal.fg, fontWeight = FontWeight.Black)
                        }
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { v -> custom = v.filter(Char::isDigit).take(2); minutes = 0; error = null },
                    label = { Text("직접 입력 (1~60분)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RectangleShape,
                    colors = sharpFieldColors(pal),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = pal.fg, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, shape = RectangleShape, onClick = { error = onSubmit(reason, min) }) {
                Text(if (ok) "${min}분 동안 해제" else "해제", color = pal.fg, fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RectangleShape) { Text("계속 집중하기", color = pal.fg) }
        },
    )
}

private val DAY_NAMES = arrayOf("일", "월", "화", "수", "목", "금", "토")

internal fun unlockWhen(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    return "%d월 %d일 (%s) %02d:%02d".format(
        c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), DAY_NAMES[c.get(Calendar.DAY_OF_WEEK) - 1],
        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
    )
}

/** "15분", "15분 요청 · 4분 만에 다시 차단", "10분 · 진행 중" */
internal fun unlockSpan(u: Unlock, now: Long): String {
    val actual = u.actualSeconds(now)
    val actualText = if (actual < 60) "${actual}초" else "${actual / 60}분"
    return when {
        u.ongoing(now) -> "${u.minutes}분 · 진행 중"
        actual < u.minutes * 60L - 30 -> "${u.minutes}분 요청 · $actualText 만에 다시 차단"
        else -> "${u.minutes}분"
    }
}

@Composable
internal fun UnlockLogHeader(count7d: Int, total: Int, pal: Palette) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("긴급 잠금 해제 기록", color = pal.fg, fontWeight = FontWeight.Black, fontSize = 17.sp)
        Text("최근 7일 ${count7d}회 · 전체 ${total}회", color = pal.muted, fontSize = 13.sp)
    }
}

@Composable
internal fun UnlockLogRow(u: Unlock, now: Long, pal: Palette) {
    val ongoing = u.ongoing(now)
    Column(
        Modifier
            .fillMaxWidth()
            .border(if (ongoing) 3.dp else 2.dp, pal.fg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(unlockWhen(u.at), color = pal.fg, fontWeight = FontWeight.Black)
        Text(unlockSpan(u, now), color = if (ongoing) pal.fg else pal.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(u.reason, color = pal.fg, fontSize = 14.sp)
    }
}
