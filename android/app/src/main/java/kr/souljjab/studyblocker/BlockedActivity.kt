package kr.souljjab.studyblocker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 차단된 앱을 열었을 때 띄우는 전체 화면. */
class BlockedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "이 앱"
        onBackPressedDispatcher.addCallback(this) { goHome() }
        val sched = Store.activeSchedule(this)
        val hard = Store.hardActive(this)
        val hardNames = Store.hardApps(this).map { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        val why = blockReason(
            Store.locked(this), Store.lockEnd(this), sched?.name,
            sched?.activeEndMillis(java.util.Calendar.getInstance()) ?: 0, Store.manual(this),
            if (hard) Store.hardEnd(this) else 0, hardNames,
        )
        // 잠긴 차단일 때만 긴급 해제 길을 보여 준다(1시간에 한 번 제한 중이면 시각만 안내).
        val next = Unlock.nextAllowed(Store.unlocks(this))
        val canUnlock = Store.lockActive(this) && !Store.unlocked(this) && !hard
        val wait = next > System.currentTimeMillis()
        val unlockLabel = when {
            !canUnlock -> null
            wait -> "긴급 해제 · ${hhmm(next)}부터 가능"
            else -> "긴급 잠금 해제"
        }
        setContent {
            StudyTheme {
                BlockedScreen(
                    label, if (hard) Store.hardEnd(this) else Store.blockEndMillis(this), why, unlockLabel, !wait,
                    { openUnlock() }, if (hard) Mode.HARD else Mode.FOCUS,
                ) { goHome() }
            }
        }
    }

    private fun openUnlock() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_UNLOCK, true),
        )
        finish()
    }

    /** 차단 화면이 떠 있는 동안 다른 차단 앱이 열리면 새 내용으로 다시 그린다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        const val EXTRA_LABEL = "label"
    }
}

/** 무엇 때문에 막혔는지: "집중 모드 · 15:30까지", "'오후 자습' 예약 · 17:00까지", "수동 차단 중". */
internal fun blockReason(
    locked: Boolean,
    lockEnd: Long,
    schedName: String?,
    schedEnd: Long,
    manual: Boolean,
    hardEnd: Long = 0,
    hardApps: List<String> = emptyList(),
): String {
    if (hardEnd > 0) {
        val apps = if (hardApps.isEmpty()) "쓸 수 있는 앱 없음 (전화만 가능)" else "쓸 수 있는 앱: ${hardApps.joinToString(", ")}"
        return "빡집중 모드 · ${hhmm(hardEnd)}까지\n$apps"
    }
    val lines = ArrayList<String>()
    if (locked) lines.add("집중 모드 · ${hhmm(lockEnd)}까지")
    if (schedName != null) lines.add("'$schedName' 예약 · ${hhmm(schedEnd)}까지")
    if (lines.isEmpty() && manual) lines.add("수동 차단 중")
    return lines.joinToString("\n")
}

@Composable
internal fun BlockedScreen(
    label: String,
    end: Long,
    why: String = "",
    unlockLabel: String? = null,
    unlockEnabled: Boolean = true,
    onUnlock: () -> Unit = {},
    mode: Mode = Mode.FOCUS,
    onDone: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val pal = palette(mode, true)
    SystemBars(pal.lightBg)

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.bg)
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        Label("Blocked", pal)
        Spacer(Modifier.height(8.dp))
        Text("$label 차단됨", color = pal.muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (why.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(why, color = pal.fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "딴짓\n금지.",
            color = pal.fg,
            fontSize = 72.sp,
            lineHeight = 72.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(24.dp))
        if (end > now) {
            Label("남은 시간", pal)
            Text(
                fmt((end - now) / 1000),
                color = pal.accent,
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            Text("수동 차단 중", color = pal.accent, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.weight(1f))
        SharpButton(if (mode == Mode.HARD) "홈으로" else "공부하러 가기", pal, onClick = onDone)
        if (unlockLabel != null) {
            Text(
                unlockLabel,
                color = pal.muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                textDecoration = if (unlockEnabled) TextDecoration.Underline else TextDecoration.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = unlockEnabled, onClick = onUnlock)
                    .padding(top = 16.dp, bottom = 4.dp),
            )
        }
    }
}
