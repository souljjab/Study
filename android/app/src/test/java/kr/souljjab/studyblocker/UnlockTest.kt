package kr.souljjab.studyblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockTest {
    @Test
    fun validation() {
        assertEquals("학교 공지 확인이 필요해요", Unlock.normalize("  학교   공지 확인이\n필요해요 "))
        assertNotNull(Unlock.error(Unlock.normalize("그냥"), 10))
        assertNotNull(Unlock.error(Unlock.normalize("          "), 10))
        assertNotNull(Unlock.error("학교 공지 확인이 필요해요", 0))
        assertNotNull(Unlock.error("학교 공지 확인이 필요해요", 61))
        assertNull(Unlock.error("학교 공지 확인이 필요해요", 60))
        assertNull(Unlock.error("abcdefghij", 1))
    }

    private fun unlock(min: Int, endedAfterSec: Long = 0) = Unlock().apply {
        at = 1_000_000L
        minutes = min
        reason = "온라인 강의 자료 확인"
        endedAt = if (endedAfterSec > 0) at + endedAfterSec * 1000 else 0
    }

    @Test
    fun actualTimeAndLabels() {
        val full = unlock(15)
        val done = full.at + 20 * 60_000L
        assertEquals(900, full.actualSeconds(done))
        assertFalse(full.ongoing(done))
        assertEquals("15분", unlockSpan(full, done))

        val early = unlock(15, endedAfterSec = 240)
        assertEquals(240, early.actualSeconds(done))
        assertEquals("15분 요청 · 4분 만에 다시 차단", unlockSpan(early, done))
        assertEquals("15분 요청 · 4초 만에 다시 차단", unlockSpan(unlock(15, endedAfterSec = 4), done))

        val running = unlock(10)
        val mid = running.at + 3 * 60_000L
        assertTrue(running.ongoing(mid))
        assertEquals(180, running.actualSeconds(mid))
        assertEquals("10분 · 진행 중", unlockSpan(running, mid))
    }
}
