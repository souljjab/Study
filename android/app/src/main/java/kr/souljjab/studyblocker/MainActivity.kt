package kr.souljjab.studyblocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StudyTheme { MainScreen() } }
    }

    override fun onResume() {
        super.onResume()
        // 차단이 켜져 있어야 하는데 서비스가 죽어 있으면 되살린다.
        if (Store.shouldBlock(this) && VpnService.prepare(this) == null) {
            startService(Intent(this, BlockVpnService::class.java))
        }
    }
}

internal data class UiState(
    val now: Long,
    val locked: Boolean,
    val manual: Boolean,
    val lockStart: Long,
    val lockEnd: Long,
    val vpnOk: Boolean,
    val accOk: Boolean,
    val todayMin: Int,
    val streak: Int,
    val sites: List<String>,
    val apps: Set<String>,
) {
    val mode: Mode
        get() = when {
            locked -> Mode.FOCUS
            manual -> Mode.MANUAL
            else -> Mode.OFF
        }
}

internal fun readState(c: Context) = UiState(
    now = System.currentTimeMillis(),
    locked = Store.locked(c),
    manual = Store.manual(c),
    lockStart = Store.lockStart(c),
    lockEnd = Store.lockEnd(c),
    vpnOk = VpnService.prepare(c) == null,
    accOk = AppGuardService.isEnabled(c),
    todayMin = Store.todayMinutes(c),
    streak = Store.streak(c),
    sites = Store.sites(c),
    apps = Store.apps(c),
)

internal class AppItem(val pkg: String, val label: String, val icon: ImageBitmap)

internal fun loadApps(c: Context): List<AppItem> {
    val pm = c.packageManager
    val blocked = Store.apps(c)
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != c.packageName }
        .mapNotNull { pkg ->
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                AppItem(
                    pkg,
                    pm.getApplicationLabel(ai).toString(),
                    pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap(),
                )
            }.getOrNull()
        }
        .sortedWith(compareBy<AppItem>({ it.pkg !in blocked }, { it.label.lowercase() }))
}

internal fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

@Composable
internal fun MainScreen() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val s = remember(tick, version) { readState(ctx) }
    val pal = palette(s.mode, isSystemInDarkTheme())
    val bg by animateColorAsState(pal.bg, label = "bg")
    SystemBars(pal.lightBg)

    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            pending?.invoke()
        } else {
            toast(ctx, "VPN 권한을 허용해야 차단할 수 있어요")
        }
        pending = null
        version++
    }

    /** VPN 권한을 확인한 뒤 action을 실행한다. 처음에는 시스템 동의 창이 뜬다. */
    fun withVpn(action: () -> Unit) {
        val i = VpnService.prepare(ctx)
        if (i == null) {
            action()
            version++
        } else {
            pending = action
            vpnLauncher.launch(i)
        }
    }

    fun startBlocking() {
        ctx.startService(Intent(ctx, BlockVpnService::class.java))
    }

    var preset by rememberSaveable { mutableIntStateOf(50) } // -1 = 직접 입력
    var custom by rememberSaveable { mutableStateOf("") }
    val minutes = if (preset > 0) preset else custom.toIntOrNull() ?: 0
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newSite by rememberSaveable { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<String?>(null) }
    val apps by produceState(emptyList<AppItem>()) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }

    fun startFocus() {
        if (minutes !in 1..1440) {
            toast(ctx, "1~1440분 사이로 정하세요")
            return
        }
        withVpn {
            val now = System.currentTimeMillis()
            val old = Store.lockEnd(ctx)
            if (old <= now) Store.setLockStart(ctx, now)
            // 진행 중이면 끝나는 시각 뒤로 연장한다. 줄이는 방법은 없다.
            Store.setLockEnd(ctx, maxOf(old, now) + minutes * 60_000L)
            Store.addFocus(ctx, minutes)
            startBlocking()
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(bg)
            .systemBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Header(s, pal) }
        item { TimerBlock(s, pal, minutes) }
        if (!s.vpnOk || !s.accOk) {
            item {
                Checklist(
                    s, pal,
                    onVpn = { withVpn {} },
                    onAcc = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
            }
        }
        item {
            Presets(preset, custom, pal, onPreset = { preset = it }, onCustom = { custom = it })
        }
        item {
            SharpButton(
                if (s.locked) "+${minutes}분 연장" else "집중 시작",
                pal,
                enabled = minutes in 1..1440,
            ) { startFocus() }
        }
        item {
            SharpButton(
                if (s.manual) "수동 차단 해제" else "지금 바로 차단 (수동)",
                pal,
                filled = false,
                enabled = !s.locked,
            ) {
                if (s.manual) {
                    Store.setManual(ctx, false)
                    startBlocking() // 서비스가 스스로 정리한다.
                    version++
                } else {
                    withVpn {
                        Store.setManual(ctx, true)
                        startBlocking()
                    }
                }
            }
        }
        item { Stats(s, pal) }
        item { Tabs(tab, s, pal) { tab = it } }
        if (tab == 0) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newSite,
                        onValueChange = { newSite = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("example.com") },
                        shape = RectangleShape,
                        colors = sharpFieldColors(pal),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    SharpButton("추가", pal, Modifier.width(84.dp)) {
                        val d = Store.normalize(newSite)
                        if (d == null) {
                            toast(ctx, "올바른 주소가 아니에요")
                        } else {
                            val list = ArrayList(s.sites)
                            if (d !in list) list.add(d)
                            Store.saveSites(ctx, list)
                            newSite = ""
                            version++
                        }
                    }
                }
            }
            items(s.sites, key = { it }) { d ->
                SiteRow(d, pal, deletable = !s.locked) { toDelete = d }
            }
        } else {
            if (apps.isEmpty()) item { Text("앱 목록 불러오는 중…", color = pal.muted) }
            items(apps, key = { it.pkg }) { a ->
                AppRow(a, a.pkg in s.apps, s.locked, pal) { on ->
                    val set = HashSet(s.apps)
                    if (on) set.add(a.pkg) else set.remove(a.pkg)
                    Store.saveApps(ctx, set)
                    version++
                }
            }
        }
        item {
            Text(
                "집중 중에는 목록에서 빼거나 차단을 끌 수 없습니다. " +
                    "단, 휴대폰 설정에서 VPN·접근성 권한을 끄면 차단이 풀립니다.",
                color = pal.muted,
                fontSize = 12.sp,
            )
        }
    }

    toDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            shape = RectangleShape,
            title = { Text("차단 목록에서 삭제") },
            text = { Text("$d 를 삭제할까요?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val list = ArrayList(Store.sites(ctx))
                        list.remove(d)
                        Store.saveSites(ctx, list)
                        toDelete = null
                        version++
                    },
                    shape = RectangleShape,
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }, shape = RectangleShape) { Text("취소") }
            },
        )
    }
}

@Composable
internal fun Header(s: UiState, pal: Palette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label("Study Block", pal)
            Text("공부 집중 차단기", color = pal.fg, fontSize = 26.sp, fontWeight = FontWeight.Black)
        }
        val tag = when (s.mode) {
            Mode.FOCUS -> "FOCUS"
            Mode.MANUAL -> "BLOCK"
            Mode.OFF -> "OFF"
        }
        Box(
            Modifier
                .border(2.dp, pal.fg)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(tag, color = pal.fg, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun BigDigits(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = if (text.length > 5) 60.sp else 76.sp,
        fontWeight = FontWeight.Black,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
internal fun TimerBlock(s: UiState, pal: Palette, minutes: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (s.mode) {
            Mode.FOCUS -> {
                val left = s.lockEnd - s.now
                val total = if (s.lockStart in 1 until s.lockEnd) s.lockEnd - s.lockStart else left
                Label("남은 시간", pal)
                BigDigits(fmt(left / 1000), pal.fg)
                Bar(left.toFloat() / total.coerceAtLeast(1), pal)
                Text("딴짓 금지. 지금은 공부할 시간.", color = pal.fg, fontWeight = FontWeight.Bold)
            }
            Mode.MANUAL -> {
                Label("수동 차단", pal)
                BigDigits("BLOCK", pal.fg)
                Text("직접 해제할 때까지 차단합니다.", color = pal.fg, fontWeight = FontWeight.Bold)
            }
            Mode.OFF -> {
                Label("집중 시간", pal)
                BigDigits(fmt(minutes * 60L), pal.fg)
                Text(
                    "시작하면 끝날 때까지 해제할 수 없습니다.",
                    color = pal.muted,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun Checklist(s: UiState, pal: Palette, onVpn: () -> Unit, onAcc: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label("처음 설정", pal)
        CheckRow("웹 차단", "VPN 권한 허용", s.vpnOk, "허용", pal, onVpn)
        CheckRow("앱 차단", "접근성에서 '공부 집중 차단기' 켜기", s.accOk, "켜기", pal, onAcc)
    }
}

@Composable
internal fun CheckRow(
    title: String,
    sub: String,
    done: Boolean,
    action: String,
    pal: Palette,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .border(2.dp, pal.fg)
                .background(if (done) pal.fg else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Text("✓", color = pal.bg, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = pal.fg, fontWeight = FontWeight.Bold)
            Text(sub, color = pal.muted, fontSize = 12.sp)
        }
        if (!done) {
            Box(
                Modifier
                    .background(pal.accent)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(action, color = pal.onAccent, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
internal fun Presets(
    preset: Int,
    custom: String,
    pal: Palette,
    onPreset: (Int) -> Unit,
    onCustom: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 50, 90, -1).forEach { p ->
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
                    Text(
                        if (p > 0) "${p}분" else "직접",
                        color = if (sel) pal.bg else pal.fg,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
        if (preset < 0) {
            OutlinedTextField(
                value = custom,
                onValueChange = { v -> onCustom(v.filter(Char::isDigit).take(4)) },
                label = { Text("분 (1~1440)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RectangleShape,
                colors = sharpFieldColors(pal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun Stats(s: UiState, pal: Palette) {
    val left = if (s.locked) ((s.lockEnd - s.now + 59_999) / 60_000).toInt() else 0
    val done = (s.todayMin - left).coerceAtLeast(0)
    val doneText = if (done >= 60) "${done / 60}시간 ${done % 60}분" else "${done}분"
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .border(2.dp, pal.fg),
    ) {
        StatCell("오늘 집중", doneText, pal, Modifier.weight(1f))
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(pal.fg),
        )
        StatCell("연속", "${s.streak}일", pal, Modifier.weight(1f))
    }
}

@Composable
internal fun StatCell(label: String, value: String, pal: Palette, modifier: Modifier) {
    Column(modifier.padding(14.dp)) {
        Label(label, pal)
        Text(value, color = pal.fg, fontSize = 20.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
internal fun Tabs(tab: Int, s: UiState, pal: Palette, onTab: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg),
    ) {
        listOf("사이트 ${s.sites.size}", "앱 ${s.apps.size}").forEachIndexed { i, t ->
            val sel = i == tab
            Box(
                Modifier
                    .weight(1f)
                    .background(if (sel) pal.fg else Color.Transparent)
                    .clickable { onTab(i) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(t, color = if (sel) pal.bg else pal.fg, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
internal fun SiteRow(domain: String, pal: Palette, deletable: Boolean, onDelete: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(domain, color = pal.fg, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(36.dp)
                    .alpha(if (deletable) 1f else 0.3f)
                    .border(2.dp, pal.fg)
                    .clickable(enabled = deletable, onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = pal.fg, fontWeight = FontWeight.Black)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(pal.muted),
        )
    }
}

@Composable
internal fun AppRow(a: AppItem, on: Boolean, locked: Boolean, pal: Palette, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(a.icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                a.label,
                color = pal.fg,
                fontWeight = if (on) FontWeight.Black else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            // 집중 중에는 차단 추가만 가능하고 해제는 막는다.
            SquareToggle(on, pal, enabled = !(locked && on), onChange = onChange)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(pal.muted),
        )
    }
}
