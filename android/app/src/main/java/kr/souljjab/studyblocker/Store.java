package kr.souljjab.studyblocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 차단 목록, 수동 차단 여부, 집중 모드 종료 시각을 저장한다. */
final class Store {
    private static final String[] DEFAULT_SITES = {
            "youtube.com", "youtu.be", "googlevideo.com", "twitch.tv", "chzzk.naver.com",
            "afreecatv.com", "netflix.com", "tving.com", "watcha.com", "wavve.com",
            "instagram.com", "facebook.com", "twitter.com", "x.com", "tiktok.com",
            "reddit.com", "dcinside.com", "fmkorea.com", "ruliweb.com", "theqoo.net",
            "webtoon.naver.com", "comic.naver.com", "namu.wiki"
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
        Collections.sort(out);
        return out;
    }

    static void saveSites(Context c, List<String> sites) {
        p(c).edit().putString("sites", String.join("\n", sites)).apply();
    }

    static boolean manual(Context c) { return p(c).getBoolean("manual", false); }

    static void setManual(Context c, boolean v) { p(c).edit().putBoolean("manual", v).apply(); }

    static long lockEnd(Context c) { return p(c).getLong("lockEnd", 0); }

    static void setLockEnd(Context c, long end) { p(c).edit().putLong("lockEnd", end).apply(); }

    static boolean locked(Context c) { return lockEnd(c) > System.currentTimeMillis(); }

    static boolean shouldBlock(Context c) { return manual(c) || locked(c); }
}
