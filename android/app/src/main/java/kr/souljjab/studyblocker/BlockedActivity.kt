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
        setContent { StudyTheme { BlockedScreen(label, Store.lockEnd(this)) { goHome() } } }
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

@Composable
internal fun BlockedScreen(label: String, end: Long, onDone: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val pal = palette(Mode.FOCUS, true)
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
        SharpButton("공부하러 가기", pal, onClick = onDone)
    }
}
