package kr.souljjab.studyblocker;

import org.json.JSONException;
import org.json.JSONObject;

/** 긴급 잠금 해제 한 번의 기록. 지우지 않고 계속 남긴다. */
public final class Unlock {
    static final int MIN_REASON = 10;
    static final int MAX_MINUTES = 60;

    long at;       // 시작(밀리초)
    int minutes;   // 요청한 해제 시간
    String reason;
    long endedAt;  // 직접 다시 차단한 시각, 시간이 다 돼 끝났으면 0

    /** 사유의 여러 공백을 하나로 줄인다. */
    static String normalize(String reason) {
        return reason == null ? "" : reason.trim().replaceAll("\\s+", " ");
    }

    /** 입력이 올바르지 않으면 이유를, 괜찮으면 null을 돌려준다. */
    static String error(String normalizedReason, int minutes) {
        int n = normalizedReason.codePointCount(0, normalizedReason.length());
        if (n < MIN_REASON) return "사유를 10자 이상 적어 주세요.";
        if (n > 300) return "사유는 300자까지 적을 수 있어요.";
        if (minutes < 1 || minutes > MAX_MINUTES) return "해제 시간은 1~60분 사이로 정하세요.";
        return null;
    }

    long plannedEnd() { return at + minutes * 60_000L; }

    /** 실제로 풀려 있던 시간(초). 진행 중이면 지금까지. */
    long actualSeconds(long now) {
        long end = plannedEnd();
        if (endedAt > 0 && endedAt < end) end = endedAt;
        if (now < end) end = now;
        return Math.max(0, (end - at) / 1000);
    }

    boolean ongoing(long now) { return endedAt == 0 && now < plannedEnd(); }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("at", at).put("minutes", minutes).put("reason", reason).put("endedAt", endedAt);
    }

    static Unlock fromJson(JSONObject o) {
        Unlock u = new Unlock();
        u.at = o.optLong("at");
        u.minutes = o.optInt("minutes");
        u.reason = o.optString("reason");
        u.endedAt = o.optLong("endedAt");
        return u;
    }
}
