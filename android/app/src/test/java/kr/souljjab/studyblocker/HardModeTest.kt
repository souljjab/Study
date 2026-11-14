package kr.souljjab.studyblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardModeTest {
    private val self = "kr.souljjab.studyblocker"
    private val chosen = setOf("com.kakao.talk", "com.sec.android.app.popupcalculator", "com.naver.dict")
    private val system = setOf("android", "com.android.systemui", "com.sec.android.app.launcher", "com.samsung.android.dialer")

    private fun allowed(pkg: String) = HardMode.allowed(pkg, self, chosen, system)

    @Test
    fun onlyChosenAndSystemScreensPass() {
        chosen.forEach { assertTrue(it, allowed(it)) }
        assertTrue(allowed(self))
        assertTrue("홈 화면", allowed("com.sec.android.app.launcher"))
        assertTrue("전화", allowed("com.samsung.android.dialer"))
        assertTrue("알림창", allowed("com.android.systemui"))
    }

    @Test
    fun everythingElseIsBlockedIncludingEscapeRoutes() {
        for (p in listOf(
            "com.google.android.youtube", "com.android.chrome",
            "com.android.settings",                 // 접근성·시계를 바꾸는 길
            "com.google.android.packageinstaller",  // 앱 삭제 창
            "com.android.vending",                  // 플레이 스토어에서 삭제
            "com.samsung.knox.securefolder",        // 보안 폴더
        )) assertFalse(p, allowed(p))
    }

    @Test
    fun startValidation() {
        assertNull(HardMode.error(60, 3, true, false))
        assertNull(HardMode.error(1, 0, true, false))
        assertNull(HardMode.error(720, 3, true, false))
        assertNotNull(HardMode.error(0, 3, true, false))
        assertNotNull(HardMode.error(721, 3, true, false))
        assertNotNull("앱 4개", HardMode.error(60, 4, true, false))
        assertNotNull("접근성 꺼짐", HardMode.error(60, 3, false, false))
        assertNotNull("이미 진행 중", HardMode.error(60, 3, true, true))
    }

    @Test
    fun blockReasonForHardMode() {
        val end = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 16); set(java.util.Calendar.MINUTE, 20)
        }.timeInMillis
        assertEquals(
            "빡집중 모드 · 16:20까지\n쓸 수 있는 앱: 카카오톡, 계산기",
            blockReason(true, end, "오후 자습", end, false, end, listOf("카카오톡", "계산기")),
        )
        assertEquals("빡집중 모드 · 16:20까지\n쓸 수 있는 앱 없음 (전화만 가능)", blockReason(false, 0, null, 0, false, end, emptyList()))
    }

    @Test
    fun durationLabels() {
        assertEquals("30분", durationText(30))
        assertEquals("1시간", durationText(60))
        assertEquals("1시간 30분", durationText(90))
    }
}
