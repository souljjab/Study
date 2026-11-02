package kr.souljjab.studyblocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Locale;

/** 차단 목록, 수동 차단 여부, 집중 모드 종료 시각을 저장한다. */
final class Store {
    private static final String[] DEFAULT_SITES = {
            "youtube.com", "youtu.be", "googlevideo.com", "twitch.tv", "chzzk.naver.com",
            "afreecatv.com", "netflix.com", "tving.com", "watcha.com", "wavve.com",
            "instagram.com", "facebook.com", "twitter.com", "x.com", "tiktok.com",
            "reddit.com", "dcinside.com", "fmkorea.com", "ruliweb.com", "theqoo.net",
            "webtoon.naver.com", "comic.naver.com", "namu.wiki",
            "ytimg.com", "youtubei.googleapis.com", "youtube.googleapis.com", "youtube-nocookie.com",
            "cdninstagram.com", "fbcdn.net", "instagr.am", "threads.net"
    };

    private static final String[] DEFAULT_APPS = {
            "com.google.android.youtube", "com.instagram.android", "com.facebook.katana",
            "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.netflix.mediaclient", "tv.twitch.android.app", "com.reddit.frontpage",
            "com.nhn.android.webtoon", "com.instagram.barcelona"
    };

    private Store() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("blocker", Context.MODE_PRIVATE);
    }

    /** URL 형태 입력에서 도메인만 뽑는다. 유효하지 않으면 null. */
    static String normalize(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        for (int k = 0; k < s.length(); k++) {
            if ("/?#:".indexOf(s.charAt(k)) >= 0) {
                s = s.substring(0, k);
                break;
            }
        }
        if (s.startsWith("www.")) s = s.substring(4);
        if (!s.contains(".") || s.contains(" ") || s.endsWith(".")) return null;
        return s;
    }

    static List<String> sites(Context c) {
        String raw = p(c).getString("sites", null);
        List<String> out = new ArrayList<>();
        if (raw == null) {
            out.addAll(Arrays.asList(DEFAULT_SITES));
        } else {
            for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        }
        if (p(c).getInt("sitesVer", 0) < 2) { // 새로 추가된 기본 도메인을 기존 목록에 합친다.
            for (String d : DEFAULT_SITES) if (!out.contains(d)) out.add(d);
            saveSites(c, out);
            p(c).edit().putInt("sitesVer", 2).apply();
        }
        Collections.sort(out);
        return out;
    }

    static Set<String> apps(Context c) {
        String raw = p(c).getString("apps", null);
        Set<String> out = new TreeSet<>();
        if (raw == null) {
            out.addAll(Arrays.asList(DEFAULT_APPS));
        } else {
            for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static void saveApps(Context c, Set<String> apps) {
        p(c).edit().putString("apps", String.join("\n", apps)).apply();
    }

    static void saveSites(Context c, List<String> sites) {
        p(c).edit().putString("sites", String.join("\n", sites)).apply();
    }

    static boolean manual(Context c) { return p(c).getBoolean("manual", false); }

    static void setManual(Context c, boolean v) { p(c).edit().putBoolean("manual", v).apply(); }

    static long lockEnd(Context c) { return p(c).getLong("lockEnd", 0); }

    static void setLockEnd(Context c, long end) { p(c).edit().putLong("lockEnd", end).apply(); }

    static long lockStart(Context c) { return p(c).getLong("lockStart", 0); }

    static void setLockStart(Context c, long t) { p(c).edit().putLong("lockStart", t).apply(); }

    private static int dayKey(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100
                + cal.get(Calendar.DAY_OF_MONTH);
    }

    /** 집중 시간을 오늘 기록에 더하고 연속 일수를 갱신한다. */
    static void addFocus(Context c, int minutes) {
        SharedPreferences sp = p(c);
        int today = dayKey(0);
        int total = sp.getInt("day", 0) == today ? sp.getInt("todayMin", 0) : 0;
        int last = sp.getInt("lastDay", 0);
        int streak = sp.getInt("streak", 0);
        if (last != today) streak = last == dayKey(-1) ? streak + 1 : 1;
        sp.edit().putInt("day", today).putInt("todayMin", total + minutes)
                .putInt("lastDay", today).putInt("streak", streak).apply();
    }

    static int todayMinutes(Context c) {
        return p(c).getInt("day", 0) == dayKey(0) ? p(c).getInt("todayMin", 0) : 0;
    }

    static int streak(Context c) {
        int last = p(c).getInt("lastDay", 0);
        return last == dayKey(0) || last == dayKey(-1) ? p(c).getInt("streak", 0) : 0;
    }

    static boolean locked(Context c) { return lockEnd(c) > System.currentTimeMillis(); }

    static boolean shouldBlock(Context c) { return manual(c) || locked(c) || activeSchedule(c) != null; }

    // ── 예약 ──

    static List<Schedule> schedules(Context c) {
        List<Schedule> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("schedules", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Schedule.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    static void saveSchedules(Context c, List<Schedule> list) {
        JSONArray a = new JSONArray();
        try {
            for (Schedule s : list) a.put(s.toJson());
        } catch (JSONException ignored) {
        }
        p(c).edit().putString("schedules", a.toString()).commit();
        ScheduleAlarm.arm(c);
    }

    static long nextScheduleId(Context c) {
        long id = p(c).getLong("nextScheduleId", 1);
        p(c).edit().putLong("nextScheduleId", id + 1).apply();
        return id;
    }

    /** 지금 켜져 있는 예약(여럿이면 가장 늦게 끝나는 것). 없으면 null. */
    static Schedule activeSchedule(Context c) {
        Calendar now = Calendar.getInstance();
        Schedule best = null;
        for (Schedule s : schedules(c)) {
            if (s.activeAt(now) && (best == null || s.activeEndMillis(now) > best.activeEndMillis(now))) best = s;
        }
        return best;
    }

    /** 차단이 끝나는 시각: 집중 모드와 예약 중 더 늦은 쪽. 수동 차단만이면 0. */
    static long blockEndMillis(Context c) {
        long end = lockEnd(c);
        Schedule s = activeSchedule(c);
        if (s != null) end = Math.max(end, s.activeEndMillis(Calendar.getInstance()));
        return end;
    }
}
