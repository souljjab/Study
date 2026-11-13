package kr.souljjab.studyblocker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class UiLogicTest {
    @Test
    fun parseTimeAcceptsOnlyValidClockTimes() {
        assertEquals(570, parseTime("09", "30"))
        assertEquals(0, parseTime("0", "0"))
        assertEquals(1439, parseTime("23", "59"))
        assertNull(parseTime("24", "00"))
        assertNull(parseTime("12", "60"))
        assertNull(parseTime("", "30"))
        assertNull(parseTime("9", ""))
    }

    @Test
    fun blockReasonNamesWhatIsBlocking() {
        fun at(h: Int, m: Int) = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
        }.timeInMillis
        assertEquals("집중 모드 · 15:30까지", blockReason(true, at(15, 30), null, 0, false))
        assertEquals("'오후 자습' 예약 · 17:00까지", blockReason(false, 0, "오후 자습", at(17, 0), true))
        assertEquals(
            "집중 모드 · 15:30까지\n'오후 자습' 예약 · 17:00까지",
            blockReason(true, at(15, 30), "오후 자습", at(17, 0), false),
        )
        assertEquals("수동 차단 중", blockReason(false, 0, null, 0, true))
        assertEquals("", blockReason(false, 0, null, 0, false))
    }

    @Test
    fun minutesTextFormats() {
        assertEquals("45분", minutesText(45))
        assertEquals("1시간 10분", minutesText(70))
    }

    @Test
    fun dayHistoryAddsAndPrunes() {
        var j = Store.addDay("{}", 20261110, 25, 20261001)
        j = Store.addDay(j, 20261110, 50, 20261001)
        j = Store.addDay(j, 20261113, 30, 20261001)
        val o = JSONObject(j)
        assertEquals(75, o.getInt("20261110"))
        assertEquals(30, o.getInt("20261113"))
        // 기준일보다 오래된 날은 지워진다
        val pruned = JSONObject(Store.addDay(j, 20261201, 10, 20261112))
        assertEquals(false, pruned.has("20261110"))
        assertEquals(30, pruned.getInt("20261113"))
        assertEquals(10, pruned.getInt("20261201"))
    }
}
