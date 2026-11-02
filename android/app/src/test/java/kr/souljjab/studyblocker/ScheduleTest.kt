package kr.souljjab.studyblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScheduleTest {
    // 2026-11-02는 월요일.
    private fun at(day: Int, h: Int, m: Int): Calendar =
        Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, day, h, m, 0) }

    private val weekday = Schedule().apply { days = 0x3E; start = 9 * 60; end = 12 * 60 }
    private val sundayNight = Schedule().apply { days = 1; start = 23 * 60; end = 7 * 60 }

    @Test
    fun weekdayWindow() {
        assertTrue(weekday.activeAt(at(2, 9, 0)))
        assertTrue(weekday.activeAt(at(2, 11, 59)))
        assertFalse(weekday.activeAt(at(2, 12, 0)))
        assertFalse(weekday.activeAt(at(1, 10, 0))) // 일요일
        assertEquals(at(2, 12, 0).timeInMillis, weekday.activeEndMillis(at(2, 10, 0)))
        assertEquals("내일 09:00", weekday.nextLabel(at(2, 13, 0)))
        assertEquals("월 09:00", weekday.nextLabel(at(6, 13, 0))) // 금 → 월
        assertEquals("오늘 09:00", weekday.nextLabel(at(2, 8, 0)))
    }

    @Test
    fun overnightBelongsToStartDay() {
        assertTrue(sundayNight.activeAt(at(1, 23, 30)))
        assertTrue(sundayNight.activeAt(at(2, 6, 59))) // 월 새벽 = 일요일 예약
        assertFalse(sundayNight.activeAt(at(2, 7, 0)))
        assertFalse(sundayNight.activeAt(at(2, 23, 30))) // 월요일 밤은 아님
        assertEquals(at(2, 7, 0).timeInMillis, sundayNight.activeEndMillis(at(1, 23, 30)))
        assertEquals(at(1, 23, 0).timeInMillis, sundayNight.activeStartMillis(at(2, 3, 0)))
        assertEquals(at(2, 7, 0).timeInMillis, sundayNight.activeEndMillis(at(2, 3, 0)))
    }

    @Test
    fun disabledOrEmpty() {
        val off = weekday.copy().apply { enabled = false }
        assertFalse(off.activeAt(at(2, 10, 0)))
        assertEquals(-1L, off.nextStartMillis(at(2, 8, 0)))
        assertEquals(-1L, Schedule().apply { start = 60; end = 120 }.nextStartMillis(at(2, 0, 0)))
        assertEquals("평일", weekday.daysLabel())
        assertEquals("월 수", Schedule().apply { days = 0b1010 }.daysLabel())
    }

    @Test
    fun activeScheduleCannotBeLoosened() {
        val edited = weekday.copy().apply { end = 10 * 60 }
        assertFalse(scheduleChangeAllowed(weekday, edited, active = true))
        assertFalse(scheduleChangeAllowed(weekday, weekday.copy().apply { enabled = false }, active = true))
        assertTrue(scheduleChangeAllowed(weekday, weekday.copy().apply { name = "오전" }, active = true))
        assertTrue(scheduleChangeAllowed(weekday, edited, active = false))
    }
}
