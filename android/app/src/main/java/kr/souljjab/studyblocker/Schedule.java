package kr.souljjab.studyblocker;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * 요일·시간대 예약. 시간이 되면 차단 목록(사이트·앱)을 저절로 막는다.
 * 끝 시각이 시작보다 이르면 다음 날까지 이어지는 밤 예약이며, 시작한 요일에 속한다.
 */
public final class Schedule {
    long id;
    String name = "예약";
    int days;  // 비트 d = 요일(0=일 … 6=토)
    int start; // 0시부터의 분
    int end;
    boolean enabled = true;

    static final String[] DAY_NAMES = {"일", "월", "화", "수", "목", "금", "토"};

    boolean hasDay(int d) { return (days & (1 << d)) != 0; }

    boolean overnight() { return end < start; }

    private static int minuteOf(Calendar c) {
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static int weekday(Calendar c) { return c.get(Calendar.DAY_OF_WEEK) - 1; }

    /** now가 오늘 기준 dayOffset일 뒤의 minute 시각인 Calendar. */
    private static long at(Calendar now, int dayOffset, int minute) {
        Calendar c = (Calendar) now.clone();
        c.add(Calendar.DAY_OF_YEAR, dayOffset);
        c.set(Calendar.HOUR_OF_DAY, minute / 60);
        c.set(Calendar.MINUTE, minute % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    boolean activeAt(Calendar now) {
        if (!enabled || start == end) return false;
        int m = minuteOf(now);
        int wd = weekday(now);
        if (start < end) return hasDay(wd) && m >= start && m < end;
        return (hasDay(wd) && m >= start) || (hasDay((wd + 6) % 7) && m < end);
    }

    /** 지금 켜져 있는 시간대의 시작 시각(밀리초). activeAt이 참일 때만 의미가 있다. */
    long activeStartMillis(Calendar now) {
        return overnight() && minuteOf(now) < end ? at(now, -1, start) : at(now, 0, start);
    }

    /** 지금 켜져 있는 시간대의 끝 시각(밀리초). */
    long activeEndMillis(Calendar now) {
        return overnight() && minuteOf(now) >= start ? at(now, 1, end) : at(now, 0, end);
    }

    /** 다음 시작 시각. 없으면 -1. */
    long nextStartMillis(Calendar now) {
        if (!enabled || days == 0 || start == end) return -1;
        long t0 = now.getTimeInMillis();
        int wd = weekday(now);
        for (int d = 0; d <= 7; d++) {
            if (!hasDay((wd + d) % 7)) continue;
            long t = at(now, d, start);
            if (t > t0) return t;
        }
        return -1;
    }

    /** "오늘 09:00", "내일 09:00", "월 09:00" */
    String nextLabel(Calendar now) {
        long t = nextStartMillis(now);
        if (t < 0) return "";
        Calendar c = (Calendar) now.clone();
        c.setTimeInMillis(t);
        Calendar today = (Calendar) now.clone();
        int diff = c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR);
        if (diff < 0) diff += today.getActualMaximum(Calendar.DAY_OF_YEAR);
        String day = diff == 0 ? "오늘" : diff == 1 ? "내일" : DAY_NAMES[weekday(c)];
        return day + " " + hm(start);
    }

    static String hm(int minute) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    String daysLabel() {
        if (days == 0x7F) return "매일";
        if (days == 0x3E) return "평일";
        if (days == 0x41) return "주말";
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 7; i++) { // 월요일부터
            int d = i % 7;
            if (hasDay(d)) b.append(b.length() == 0 ? "" : " ").append(DAY_NAMES[d]);
        }
        return b.length() == 0 ? "요일 없음" : b.toString();
    }

    Schedule copy() {
        Schedule s = new Schedule();
        s.id = id;
        s.name = name;
        s.days = days;
        s.start = start;
        s.end = end;
        s.enabled = enabled;
        return s;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("days", days)
                .put("start", start).put("end", end).put("enabled", enabled);
    }

    static Schedule fromJson(JSONObject o) {
        Schedule s = new Schedule();
        s.id = o.optLong("id");
        s.name = o.optString("name", "예약");
        s.days = o.optInt("days") & 0x7F;
        s.start = Math.max(0, Math.min(1439, o.optInt("start")));
        s.end = Math.max(0, Math.min(1439, o.optInt("end")));
        s.enabled = o.optBoolean("enabled", true);
        return s;
    }
}
