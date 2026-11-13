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
            FocusFooter(s, pal) {}
        }
    }

    /** 실제 화면처럼 내용 아래에 하단 메뉴를 붙인다. */
    @Composable
    private fun WithNav(page: Int, pal: Palette, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(pal.bg)) {
            Column(Modifier.weight(1f)) { content() }
            BottomNav(page, pal) {}
        }
    }

    @Composable
    private fun FocusPage(s: UiState) = WithNav(0, palette(s.mode, false)) { Screen(s) }

    @Test
    fun listPage() {
        val s = state()
        val pal = palette(s.mode, false)
        paparazzi.snapshot {
            StudyTheme {
                WithNav(1, pal) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Header(s, pal) {}
                        Tabs(0, s, pal) {}
                        sites.forEach { SiteRow(it, pal, deletable = true) {} }
                    }
                }
            }
        }
    }

    @Test
    fun records() {
        val base = state()
        fun u(agoMin: Long, min: Int, reason: String, endedAfterSec: Long = 0) = Unlock().apply {
            at = now - agoMin * 60_000L
            minutes = min
            this.reason = reason
            endedAt = if (endedAfterSec > 0) at + endedAfterSec * 1000 else 0
        }
        val log = listOf(
            u(26 * 60, 15, "온라인 강의 자료가 유튜브에만 올라와 있어요", endedAfterSec = 240),
            u(3 * 24 * 60, 5, "부모님께 급히 연락해야 했어요"),
        )
        val s = base.copy(unlocks = log, week = listOf(95, 140, 60, 180, 125, 30, 50))
        val pal = palette(s.mode, false)
        paparazzi.snapshot {
            StudyTheme {
                WithNav(3, pal) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Header(s, pal) {}
                        Stats(s, pal)
                        WeekChart(s.week, s.todayDone, s.now, pal)
                        UnlockLogHeader(2, 2, pal)
                        log.forEach { UnlockLogRow(it, now, pal) }
                    }
                }
            }
        }
    }

    @Test
    fun off_firstRun() = paparazzi.snapshot { StudyTheme { FocusPage(state(setupDone = false)) } }

    @Test
    fun focus() = paparazzi.snapshot { StudyTheme { FocusPage(state(locked = true)) } }

    @Test
    fun manual() = paparazzi.snapshot { StudyTheme { FocusPage(state(manual = true)) } }

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
    fun schedule() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 3300))
        // 화면 시계: 오늘 14:13, '오후 자습'(13:00–17:00)이 적용 중
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 14); set(java.util.Calendar.MINUTE, 13); set(java.util.Calendar.SECOND, 0) }
        val now = cal.timeInMillis
        val morning = Schedule().apply { id = 1; name = "오후 자습"; days = 0x7F; start = 13 * 60; end = 17 * 60 }
        val night = Schedule().apply { id = 2; name = "매일 밤"; days = 0x7F; start = 23 * 60; end = 7 * 60 }
        val weekend = Schedule().apply { id = 3; name = "주말 오전"; days = 0x41; start = 9 * 60; end = 12 * 60; enabled = false }
        val s = state().copy(
            now = now,
            schedules = listOf(morning, night, weekend), activeSched = morning,
            schedStart = morning.activeStartMillis(cal), schedEnd = morning.activeEndMillis(cal),
            schedNext = mapOf(2L to "오늘 23:00", 3L to ""),
        )
        val pal = palette(s.mode, false)
        paparazzi.snapshot {
            StudyTheme {
                Column(
                    Modifier.fillMaxSize().background(pal.bg).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Header(s, pal) {}
                    TimerBlock(s, pal, 50)
                    ScheduleIntro(pal, {}, {})
                    ScheduleRow(morning, true, "", s.schedEnd, pal, {}, {}, {})
                    ScheduleRow(night, false, "오늘 23:00", 0, pal, {}, {}, {})
                    ScheduleRow(weekend, false, "", 0, pal, {}, {}, {})
                    TimeFields("시작", "09", "30", pal, true, {}, {})
                    TimeFields("끝", "25", "00", pal, true, {}, {})
                }
            }
        }
    }

    @Test
    fun unlock() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 2400))
        val base = state(locked = true)
        val now = base.now
        fun u(agoMin: Long, min: Int, reason: String, endedAfterSec: Long = 0) = Unlock().apply {
            at = now - agoMin * 60_000L
            minutes = min
            this.reason = reason
            endedAt = if (endedAfterSec > 0) at + endedAfterSec * 1000 else 0
        }
        val log = listOf(
            u(3, 10, "학원 단톡방 공지를 확인해야 해요"),
            u(26 * 60, 15, "온라인 강의 자료가 유튜브에만 올라와 있어요", endedAfterSec = 240),
            u(3 * 24 * 60, 5, "부모님께 급히 연락해야 했어요"),
        )
        val s = base.copy(unlockUntil = now + 7 * 60_000L, unlocks = log)
        val pal = palette(s.mode, false)
        paparazzi.snapshot {
            StudyTheme {
                Column(
                    Modifier.fillMaxSize().background(pal.bg).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Header(s, pal) {}
                    UnlockBanner(s.unlockUntil - now, log[0].reason, pal) {}
                    TimerBlock(s, pal, 50)
                    FocusFooter(s, pal) {}
                    UnlockLogHeader(2, 3, pal)
                    log.forEach { UnlockLogRow(it, now, pal) }
                }
            }
        }
    }

    @Test
    fun secureFolder() {
        val s = state(locked = true)
        val pal = palette(s.mode, false)
        paparazzi.snapshot {
            StudyTheme {
                Column(
                    Modifier.fillMaxSize().background(pal.bg).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Header(s, pal) {}
                    Tabs(1, s, pal) {}
                    SecureFolderRow(on = true, locked = true, pal = pal) {}
                }
            }
        }
    }

    @Test
    fun blocked() = paparazzi.snapshot {
        StudyTheme {
            BlockedScreen(
                "YouTube", System.currentTimeMillis() + (29 * 60 + 30) * 1000L,
                why = "집중 모드 · 15:30까지\n'오후 자습' 예약 · 17:00까지", unlockLabel = "긴급 잠금 해제",
            ) {}
        }
    }
}
