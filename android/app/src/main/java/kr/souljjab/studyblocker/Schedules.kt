package kr.souljjab.studyblocker

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 월요일부터 보여 줄 요일 순서(0=일). */
private val DAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)

internal data class Template(val name: String, val days: Int, val start: Int, val end: Int, val ex: String)

internal val TEMPLATES = listOf(
    Template("평일 오전", 0x3E, 9 * 60, 12 * 60, "월–금 09:00–12:00"),
    Template("평일 저녁", 0x3E, 19 * 60, 22 * 60, "월–금 19:00–22:00"),
    Template("매일 밤", 0x7F, 23 * 60, 7 * 60, "매일 23:00–07:00"),
)

internal fun newSchedule(c: Context, t: Template? = null): Schedule = Schedule().apply {
    id = Store.nextScheduleId(c)
    name = t?.name ?: "새 예약"
    days = t?.days ?: 0x3E
    start = t?.start ?: 9 * 60
    end = t?.end ?: 12 * 60
}

/** 적용 중인 예약은 끝날 때까지 끄거나 시간·요일을 바꿀 수 없다(이름만 가능). */
internal fun scheduleChangeAllowed(old: Schedule?, new: Schedule, active: Boolean): Boolean {
    if (old == null || !active) return true
    return old.days == new.days && old.start == new.start && old.end == new.end && new.enabled
}

internal fun saveSchedule(c: Context, sc: Schedule) {
    val list = Store.schedules(c)
    val i = list.indexOfFirst { it.id == sc.id }
    if (i >= 0) list[i] = sc else list.add(sc)
    Store.saveSchedules(c, list)
}

internal fun deleteSchedule(c: Context, id: Long) {
    Store.saveSchedules(c, Store.schedules(c).filter { it.id != id })
}

@Composable
internal fun ScheduleIntro(pal: Palette, onAdd: () -> Unit, onTemplate: (Template) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "정한 요일·시간이 되면 앱을 열지 않아도 차단 목록(사이트·앱)을 저절로 막고, 끝나면 풀어요. " +
                "적용 중인 예약은 끝날 때까지 끄거나 바꿀 수 없어요.",
            color = pal.fg,
            fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TEMPLATES.forEach { t ->
                Column(
                    Modifier
                        .weight(1f)
                        .border(2.dp, pal.fg)
                        .clickable { onTemplate(t) }
                        .padding(10.dp),
                ) {
                    Text("+ ${t.name}", color = pal.fg, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    Text(t.ex, color = pal.muted, fontSize = 11.sp)
                }
            }
        }
        SharpButton("새 예약 만들기", pal, filled = false, onClick = onAdd)
    }
}

@Composable
private fun Tag(text: String, pal: Palette, filled: Boolean) {
    Box(
        Modifier
            .background(if (filled) pal.accent else Color.Transparent)
            .border(2.dp, if (filled) pal.accent else pal.fg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = if (filled) pal.onAccent else pal.fg, fontSize = 12.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SmallBox(text: String, pal: Palette, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .border(2.dp, pal.fg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = pal.fg, fontWeight = FontWeight.Black, fontSize = 13.sp)
    }
}

@Composable
internal fun ScheduleRow(
    sc: Schedule,
    active: Boolean,
    next: String,
    activeEnd: Long,
    pal: Palette,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (active) pal.fg.copy(alpha = 0.08f) else Color.Transparent)
            .border(if (active) 3.dp else 2.dp, if (active) pal.accent else pal.fg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sc.name, color = pal.fg, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            when {
                active -> Tag("차단 중 · ${Schedule.hm(minuteOfDay(activeEnd))}까지", pal, true)
                !sc.enabled -> Tag("꺼짐", pal, false)
                sc.days == 0 -> Tag("요일 없음", pal, false)
                else -> Tag("다음 · $next", pal, false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${Schedule.hm(sc.start)} – ${Schedule.hm(sc.end)}" + if (sc.overnight()) " (다음 날)" else "",
                    color = pal.fg,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                Text(sc.daysLabel(), color = pal.muted, fontSize = 13.sp)
            }
            // 적용 중인 예약은 끌 수 없다.
            SquareToggle(sc.enabled, pal, enabled = !(active && sc.enabled), onChange = onToggle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallBox("수정", pal, onClick = onEdit)
            SmallBox("삭제", pal, enabled = !active, onClick = onDelete)
            if (active) Text("끝날 때까지 시간·요일은 바꿀 수 없어요", color = pal.muted, fontSize = 12.sp)
        }
    }
}

private fun minuteOfDay(millis: Long): Int {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
}

@Composable
private fun Stepper(value: Int, label: String, pal: Palette, onChange: (Int) -> Unit, step: Int, mod: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallBox("−", pal) { onChange(((value - step) % mod + mod) % mod) }
        Text(
            "%02d".format(value),
            color = pal.fg,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        SmallBox("+", pal) { onChange((value + step) % mod) }
        Spacer(Modifier.width(4.dp))
        Text(label, color = pal.muted, fontSize = 12.sp)
    }
}

@Composable
private fun TimeEditor(title: String, minute: Int, pal: Palette, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(Modifier.alpha(if (enabled) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(title, pal)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Stepper(minute / 60, "시", pal, { if (enabled) onChange(it * 60 + minute % 60) }, 1, 24)
            Stepper(minute % 60, "분", pal, { if (enabled) onChange(minute / 60 * 60 + it) }, 10, 60)
        }
    }
}

/** 예약 추가·수정 대화상자. lockedTimes면 이름만 바꿀 수 있다. */
@Composable
internal fun ScheduleEditor(
    initial: Schedule,
    isNew: Boolean,
    lockedTimes: Boolean,
    pal: Palette,
    onSave: (Schedule) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var days by remember { mutableIntStateOf(initial.days) }
    var start by remember { mutableIntStateOf(initial.start) }
    var end by remember { mutableIntStateOf(initial.end) }
    val error = when {
        days == 0 -> "요일을 하나 이상 고르세요."
        start == end -> "시작과 끝 시간이 같아요."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = pal.bg,
        title = { Text(if (isNew) "새 예약" else "예약 수정", color = pal.fg, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("이름") },
                    singleLine = true,
                    shape = RectangleShape,
                    colors = sharpFieldColors(pal),
                    modifier = Modifier.fillMaxWidth(),
                )
                TimeEditor("시작", start, pal, !lockedTimes) { start = it }
                TimeEditor("끝", end, pal, !lockedTimes) { end = it }
                if (end < start) Text("끝이 시작보다 이르면 다음 날 ${Schedule.hm(end)}까지 이어져요.", color = pal.muted, fontSize = 12.sp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label("요일", pal)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DAY_ORDER.forEach { d ->
                            val on = days and (1 shl d) != 0
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .alpha(if (lockedTimes) 0.4f else 1f)
                                    .border(2.dp, pal.fg)
                                    .background(if (on) pal.fg else Color.Transparent)
                                    .clickable(enabled = !lockedTimes) { days = days xor (1 shl d) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(Schedule.DAY_NAMES[d], color = if (on) pal.bg else pal.fg, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            }
                        }
                    }
                }
                if (lockedTimes) Text("적용 중인 예약이라 이름만 바꿀 수 있어요.", color = pal.muted, fontSize = 12.sp)
                error?.let { Text(it, color = pal.fg, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null,
                shape = RectangleShape,
                onClick = {
                    onSave(initial.copy().also {
                        it.name = name.trim().ifEmpty { "예약" }
                        it.days = days
                        it.start = start
                        it.end = end
                    })
                },
            ) { Text("저장", color = pal.fg, fontWeight = FontWeight.Black) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RectangleShape) { Text("취소", color = pal.fg) }
        },
    )
}
