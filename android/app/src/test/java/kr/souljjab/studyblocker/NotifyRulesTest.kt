package kr.souljjab.studyblocker

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyRulesTest {
    private val self = "kr.souljjab.studyblocker"

    @Test
    fun whichNotificationsAreHidden() {
        assertTrue("메신저", NotifyRules.shouldHide("com.kakao.talk", self, true, Notification.CATEGORY_MESSAGE))
        assertTrue("카테고리 없음", NotifyRules.shouldHide("com.instagram.android", self, true, null))
        assertFalse("우리 앱", NotifyRules.shouldHide(self, self, true, null))
        assertFalse("음악 재생 등 지울 수 없는 알림", NotifyRules.shouldHide("com.spotify.music", self, false, Notification.CATEGORY_TRANSPORT))
        assertFalse("걸려 오는 전화", NotifyRules.shouldHide("com.samsung.android.dialer", self, true, Notification.CATEGORY_CALL))
        assertFalse("알람", NotifyRules.shouldHide("com.sec.android.app.clockpackage", self, true, Notification.CATEGORY_ALARM))
    }

    @Test
    fun snoozeUntilBlockEndButAtMost30Minutes() {
        val now = 1_000_000_000L
        assertEquals(10 * 60_000L + 5_000L, NotifyRules.snoozeMs(now, now + 10 * 60_000L)) // 집중 10분 남음
        assertEquals(30 * 60_000L, NotifyRules.snoozeMs(now, now + 3 * 3600_000L))         // 길면 30분씩
        assertEquals(60_000L, NotifyRules.snoozeMs(now, now + 1_000L))                     // 최소 1분
        assertEquals(15 * 60_000L, NotifyRules.snoozeMs(now, 0))                           // 끝 시각 모름(수동)
    }
}
