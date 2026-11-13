package kr.souljjab.studyblocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** 차단 화면의 '긴급 잠금 해제'로 들어왔을 때 해제 창을 바로 연다. */
    private var unlockRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) unlockRequest = intent?.getBooleanExtra(EXTRA_UNLOCK, false) == true
        setContent { StudyTheme { MainScreen(unlockRequest) { unlockRequest = false } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_UNLOCK, false)) unlockRequest = true
    }

    companion object {
        const val EXTRA_UNLOCK = "unlock"
    }

    override fun onResume() {
        super.onResume()
        // 차단이 켜져 있어야 하는데 서비스가 죽어 있으면 되살린다.
        if (Store.shouldBlock(this) && VpnService.prepare(this) == null) {
            startService(Intent(this, BlockVpnService::class.java))
        }
        ScheduleAlarm.arm(this)
    }
}

internal data class UiState(
    val now: Long,
    val locked: Boolean,
    val manual: Boolean,
    val lockStart: Long,
    val lockEnd: Long,
    val perms: Perms,
    val todayMin: Int,
    val streak: Int,
    val sites: List<String>,
    val apps: Set<String>,
    val schedules: List<Schedule> = emptyList(),
    val activeSched: Schedule? = null,
    val schedStart: Long = 0,
    val schedEnd: Long = 0,
    val schedNext: Map<Long, String> = emptyMap(),
    val unlockUntil: Long = 0,
    val unlocks: List<Unlock> = emptyList(),
    val secureFolder: Boolean = true,
    val secureFolderAvailable: Boolean = false,
    val week: List<Int> = List(7) { 0 }, // 최근 7일 집중 분, 마지막이 오늘
) {
    /** 오늘 실제로 집중한 분(진행 중인 집중의 남은 시간은 뺀다). */
    val todayDone: Int
        get() = (todayMin - if (locked) ((lockEnd - now + 59_999) / 60_000).toInt() else 0).coerceAtLeast(0)

    val unlocking: Boolean get() = unlockUntil > now

    /** 긴급 해제 버튼을 보여 줄지: 잠긴 차단이 있고 이미 해제 중이 아닐 때. */
    val canUnlock: Boolean get() = (locked || activeSched != null) && !unlocking

    /** 1시간에 한 번 제한: 다음 긴급 해제가 가능한 시각. 지금 가능하면 now 이하. */
    val nextUnlockAt: Long get() = Unlock.nextAllowed(unlocks)
    val unlockWait: Boolean get() = nextUnlockAt > now

    val mode: Mode
        get() = when {
            locked -> Mode.FOCUS
            activeSched != null -> Mode.SCHEDULE
            manual -> Mode.MANUAL
            else -> Mode.OFF
        }

    /** 집중 중이거나 예약 시간이면 목록에서 빼는 것을 막는다. */
    val listLocked: Boolean get() = locked || activeSched != null
}

internal fun readState(c: Context) = UiState(
    now = System.currentTimeMillis(),
    locked = Store.locked(c),
    manual = Store.manual(c),
    lockStart = Store.lockStart(c),
    lockEnd = Store.lockEnd(c),
    perms = readPerms(c),
    todayMin = Store.todayMinutes(c),
    streak = Store.streak(c),
    sites = Store.sites(c),
    apps = Store.apps(c),
).let { s ->
    val now = java.util.Calendar.getInstance()
    val list = Store.schedules(c)
    val act = Store.activeSchedule(c)
    s.copy(
        schedules = list,
        activeSched = act,
        schedStart = act?.activeStartMillis(now) ?: 0,
        schedEnd = act?.activeEndMillis(now) ?: 0,
        schedNext = list.associate { it.id to it.nextLabel(now) },
        unlockUntil = Store.unlockUntil(c),
        unlocks = Store.unlocks(c),
        secureFolder = Store.secureFolderBlock(c),
        secureFolderAvailable = SecureFolder.available(c),
        week = Store.weekMinutes(c).toList(),
    )
}

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
internal fun MainScreen(unlockRequest: Boolean = false, onUnlockRequestHandled: () -> Unit = {}) {
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
    var page by rememberSaveable { mutableIntStateOf(0) } // 0 집중, 1 목록, 2 예약, 3 기록
    var tab by rememberSaveable { mutableIntStateOf(0) } // 목록 화면: 0 사이트, 1 앱
    var newSite by rememberSaveable { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<Schedule, Boolean>?>(null) } // 예약, 새로 만드는지
    var schedDelete by remember { mutableStateOf<Schedule?>(null) }
    var unlockOpen by remember { mutableStateOf(false) }
    LaunchedEffect(unlockRequest) {
        if (unlockRequest) {
            if (s.canUnlock && !s.unlockWait) unlockOpen = true
            onUnlockRequestHandled()
        }
    }
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

    // 필수 권한이 없으면 처음에 권한 화면부터 보여 준다.
    var showPerms by rememberSaveable { mutableStateOf(!s.perms.requiredDone) }
    if (showPerms) {
        BackHandler { showPerms = false }
        PermissionScreen(
            s.perms, pal,
            showRestrictedHelp = restrictedSettingsOs,
            onVpn = { withVpn {} },
            onAcc = { openAccessibilitySettings(ctx) },
            onAppInfo = { openAppInfo(ctx) },
            onBattery = { requestBatteryExemption(ctx) },
            onAlwaysOn = { openVpnSettings(ctx) },
            onDone = { showPerms = false },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header(s, pal) { showPerms = true } }
            if (s.unlocking) {
                item {
                    UnlockBanner(s.unlockUntil - s.now, s.unlocks.firstOrNull()?.reason ?: "", pal) {
                        Store.endUnlock(ctx)
                        startBlocking()
                        version++
                    }
                }
            }
            if (!s.perms.requiredDone) item { PermBanner(s.perms, pal) { showPerms = true } }
            when (page) {
                0 -> {
                    item { TimerBlock(s, pal, minutes) }
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
                    item { FocusFooter(s, pal) { unlockOpen = true } }
                }
                1 -> {
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
                            SiteRow(d, pal, deletable = !s.listLocked) { toDelete = d }
                        }
                    } else {
                        if (s.secureFolderAvailable) {
                            item {
                                SecureFolderRow(s.secureFolder, s.listLocked, pal) { on ->
                                    Store.setSecureFolderBlock(ctx, on)
                                    version++
                                }
                            }
                        }
                        if (apps.isEmpty()) item { Text("앱 목록 불러오는 중…", color = pal.muted) }
                        items(apps, key = { it.pkg }) { a ->
                            AppRow(a, a.pkg in s.apps, s.listLocked, pal) { on ->
                                val set = HashSet(s.apps)
                                if (on) set.add(a.pkg) else set.remove(a.pkg)
                                Store.saveApps(ctx, set)
                                version++
                            }
                        }
                    }
                    item {
                        Text(
                            "집중 중이거나 예약 시간에는 목록에서 빼거나 차단을 끌 수 없습니다. " +
                                "단, 휴대폰 설정에서 VPN·접근성 권한을 끄면 차단이 풀립니다.",
                            color = pal.muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                2 -> {
                    item {
                        ScheduleIntro(
                            pal,
                            onAdd = { editing = newSchedule(ctx) to true },
                            onTemplate = { t -> withVpn { saveSchedule(ctx, newSchedule(ctx, t)); ScheduleAlarm.ensureBlocking(ctx) } },
                        )
                    }
                    if (s.schedules.isEmpty()) item { Text("아직 예약이 없어요.", color = pal.muted) }
                    items(s.schedules, key = { it.id }) { sc ->
                        val active = s.activeSched?.id == sc.id || (sc.activeAt(java.util.Calendar.getInstance()))
                        ScheduleRow(
                            sc, active, s.schedNext[sc.id] ?: "", if (active) sc.activeEndMillis(java.util.Calendar.getInstance()) else 0, pal,
                            onToggle = { on ->
                                val next = sc.copy().also { it.enabled = on }
                                if (!scheduleChangeAllowed(sc, next, active)) {
                                    toast(ctx, "적용 중인 예약은 끝날 때까지 끌 수 없어요")
                                } else if (on) {
                                    withVpn { saveSchedule(ctx, next); ScheduleAlarm.ensureBlocking(ctx) }
                                } else {
                                    saveSchedule(ctx, next)
                                    version++
                                }
                            },
                            onEdit = { editing = sc.copy() to false },
                            onDelete = { if (active) toast(ctx, "적용 중인 예약은 지울 수 없어요") else schedDelete = sc },
                        )
                    }
                }
                else -> {
                    item { Stats(s, pal) }
                    item { WeekChart(s.week, s.todayDone, s.now, pal) }
                    item { UnlockLogHeader(s.unlocks.count { it.at >= s.now - 7 * 24 * 3600_000L }, s.unlocks.size, pal) }
                    if (s.unlocks.isEmpty()) item { Text("긴급 잠금 해제를 한 적이 없어요.", color = pal.muted) }
                    items(s.unlocks, key = { it.at }) { u -> UnlockLogRow(u, s.now, pal) }
                }
            }
        }
        BottomNav(page, pal) { page = it }
    }

    if (unlockOpen) {
        UnlockDialog(
            pal,
            onSubmit = { reason, min ->
                val err = Store.emergencyUnlock(ctx, reason, min)
                if (err == null) {
                    unlockOpen = false
                    startBlocking() // 서비스가 차단을 멈추고 스스로 정리한다
                    version++
                }
                err
            },
            onDismiss = { unlockOpen = false },
        )
    }
    editing?.let { (sc, isNew) ->
        val old = s.schedules.firstOrNull { it.id == sc.id }
        val active = old?.activeAt(java.util.Calendar.getInstance()) == true
        ScheduleEditor(
            sc, isNew, lockedTimes = active, pal = pal,
            onSave = { next ->
                if (!scheduleChangeAllowed(old, next, active)) {
                    toast(ctx, "적용 중인 예약은 끝날 때까지 바꿀 수 없어요")
                } else {
                    editing = null
                    withVpn { saveSchedule(ctx, next); ScheduleAlarm.ensureBlocking(ctx) }
                }
            },
            onDismiss = { editing = null },
        )
    }
    schedDelete?.let { sc ->
        AlertDialog(
            onDismissRequest = { schedDelete = null },
            shape = RectangleShape,
            title = { Text("예약 삭제") },
            text = { Text("'${sc.name}' 예약을 지울까요?") },
            confirmButton = {
                TextButton(onClick = { deleteSchedule(ctx, sc.id); schedDelete = null; version++ }, shape = RectangleShape) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { schedDelete = null }, shape = RectangleShape) { Text("취소") } },
        )
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
internal fun Header(s: UiState, pal: Palette, onPerms: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label("Study Block", pal)
            Text("공부 집중 차단기", color = pal.fg, fontSize = 26.sp, fontWeight = FontWeight.Black)
        }
        val tag = when (s.mode) {
            Mode.FOCUS -> "FOCUS"
            Mode.MANUAL -> "BLOCK"
            Mode.SCHEDULE -> "AUTO"
            Mode.OFF -> "OFF"
        }
        Box(
            Modifier
                .border(2.dp, pal.fg)
                .clickable(onClick = onPerms)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("권한", color = pal.fg, fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
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
                Text(
                    if (s.unlocking) "긴급 해제 중이에요. 타이머는 계속 돌아요." else "딴짓 금지. 지금은 공부할 시간.",
                    color = pal.fg,
                    fontWeight = FontWeight.Bold,
                )
            }
            Mode.SCHEDULE -> {
                val left = s.schedEnd - s.now
                Label("예약 차단", pal)
                BigDigits(fmt(left / 1000), pal.fg)
                Bar(left.toFloat() / (s.schedEnd - s.schedStart).coerceAtLeast(1), pal)
                Text(
                    "'${s.activeSched?.name}' 예약 · ${Schedule.hm(s.activeSched?.end ?: 0)}에 풀려요",
                    color = pal.fg,
                    fontWeight = FontWeight.Bold,
                )
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
    val doneText = minutesText(s.todayDone)
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

/** 차단 중에 삼성 보안 폴더도 막을지 정하는 줄. 집중 중·예약 시간에는 끌 수 없다. */
@Composable
internal fun SecureFolderRow(on: Boolean, locked: Boolean, pal: Palette, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("삼성 보안 폴더 차단", color = pal.fg, fontWeight = FontWeight.Black)
            Text(
                "차단 중에는 보안 폴더와 그 안의 앱을 열 수 없어요. 보안 폴더 안에는 웹 차단이 닿지 않아서 통째로 막아요.",
                color = pal.muted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        SquareToggle(on, pal, enabled = !(locked && on), onChange = onChange)
    }
}

internal fun minutesText(m: Int): String = if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"

/** 화면 맨 아래 메뉴. 선택된 칸은 채운다. */
@Composable
internal fun BottomNav(page: Int, pal: Palette, onPage: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(pal.bg)
            .border(2.dp, pal.fg),
    ) {
        listOf("집중", "목록", "예약", "기록").forEachIndexed { i, t ->
            val sel = i == page
            Box(
                Modifier
                    .weight(1f)
                    .background(if (sel) pal.fg else Color.Transparent)
                    .clickable { onPage(i) }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(t, color = if (sel) pal.bg else pal.fg, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
        }
    }
}

/** 집중 화면 맨 아래: 오늘 요약과, 잠겨 있을 때만 보이는 작은 긴급 해제 링크. */
@Composable
internal fun FocusFooter(s: UiState, pal: Palette, onUnlock: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "오늘 ${minutesText(s.todayDone)} · 연속 ${s.streak}일",
            color = pal.muted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (s.canUnlock) {
            if (s.unlockWait) {
                Text("긴급 해제 · ${hhmm(s.nextUnlockAt)}부터", color = pal.muted, fontSize = 13.sp)
            } else {
                Text(
                    "긴급 잠금 해제",
                    color = pal.muted,
                    fontSize = 13.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable(onClick = onUnlock)
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** 최근 7일 집중 시간 막대그래프. week의 마지막이 오늘이다. */
@Composable
internal fun WeekChart(week: List<Int>, todayDone: Int, now: Long, pal: Palette) {
    val vals = week.dropLast(1) + todayDone
    val maxV = maxOf(vals.max(), 60)
    val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Label("최근 7일", pal) }
            Text("합계 ${minutesText(vals.sum())}", color = pal.fg, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(170.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            vals.forEachIndexed { i, m ->
                val today = i == vals.lastIndex
                val day = java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    add(java.util.Calendar.DAY_OF_YEAR, i - vals.lastIndex)
                }.get(java.util.Calendar.DAY_OF_WEEK) - 1
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (m > 0) "%d:%02d".format(m / 60, m % 60) else "—",
                        color = if (today) pal.fg else pal.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((m * 100f / maxV).coerceAtLeast(2f).dp)
                            .background(if (today) pal.accent else pal.fg.copy(alpha = 0.35f)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (today) "오늘" else names[day],
                        color = if (today) pal.fg else pal.muted,
                        fontSize = 12.sp,
                        fontWeight = if (today) FontWeight.Black else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
