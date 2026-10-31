package kr.souljjab.studyblocker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** 화면 디자인 확인용 스크린샷. `./gradlew recordPaparazziDebug`로 갱신한다. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private val now = 1_700_000_000_000L
    private val sites = listOf("instagram.com", "netflix.com", "youtube.com")

    private fun state(locked: Boolean = false, manual: Boolean = false, setupDone: Boolean = true) = UiState(
        now = now,
        locked = locked,
        manual = manual,
        lockStart = now - 20 * 60_000L,
        lockEnd = if (locked) now + (29 * 60 + 47) * 1000L else 0,
        perms = Perms(vpn = setupDone, acc = setupDone, battery = setupDone),
        todayMin = if (locked) 100 else 50,
        streak = 3,
        sites = sites,
        apps = setOf("com.google.android.youtube"),
    )

    @Composable
    private fun Screen(s: UiState) {
        val pal = palette(s.mode, false)
        Column(
            Modifier
                .fillMaxSize()
                .background(pal.bg)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(s, pal) {}
            if (!s.perms.requiredDone) PermBanner(s.perms, pal) {}
            TimerBlock(s, pal, 50)
            Presets(50, "", pal, {}, {})
            SharpButton(if (s.locked) "+50분 연장" else "집중 시작", pal) {}
            SharpButton(if (s.manual) "수동 차단 해제" else "지금 바로 차단 (수동)", pal, filled = false, enabled = !s.locked) {}
            Stats(s, pal)
            Tabs(0, s, pal) {}
            sites.forEach { SiteRow(it, pal, deletable = !s.locked) {} }
        }
    }

    @Test
    fun off_firstRun() = paparazzi.snapshot { StudyTheme { Screen(state(setupDone = false)) } }

    @Test
    fun focus() = paparazzi.snapshot { StudyTheme { Screen(state(locked = true)) } }

    @Test
    fun manual() = paparazzi.snapshot { StudyTheme { Screen(state(manual = true)) } }

    @Test
    fun permissions() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 3200))
        paparazzi.snapshot {
        StudyTheme {
            PermissionScreen(
                Perms(vpn = true, acc = false, battery = false), palette(Mode.OFF, false),
                showRestrictedHelp = true, {}, {}, {}, {}, {}, {},
            )
        }
        }
    }

    @Test
    fun blocked() = paparazzi.snapshot { StudyTheme { BlockedScreen("YouTube", System.currentTimeMillis() + (29 * 60 + 30) * 1000L) {} } }
}
