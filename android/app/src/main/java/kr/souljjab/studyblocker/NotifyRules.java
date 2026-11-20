package kr.souljjab.studyblocker;

import android.app.Notification;

/** 집중 중 알림 차단에서 어떤 알림을 숨기고 얼마나 미룰지 정한다. */
public final class NotifyRules {
    /** 한 번에 미루는 최대 시간. 집중이 일찍 끝나도(긴급 해제·수동 해제) 이 안에 알림이 돌아온다. */
    static final long MAX_SNOOZE_MS = 30 * 60_000L;
    /** 끝나는 시각을 모르는 수동 차단일 때 미루는 시간. */
    static final long OPEN_ENDED_MS = 15 * 60_000L;

    private NotifyRules() {}

    /**
     * 이 알림을 숨길지. 우리 앱 알림, 지울 수 없는 알림(음악 재생·내비·전경 서비스),
     * 걸려 오는 전화와 알람은 그대로 둔다.
     */
    static boolean shouldHide(String pkg, String self, boolean clearable, String category) {
        if (pkg.equals(self) || !clearable) return false;
        return !Notification.CATEGORY_CALL.equals(category) && !Notification.CATEGORY_ALARM.equals(category);
    }

    /** 미룰 시간(밀리초). 차단이 끝나는 시각에 돌아오도록 하되 최대 30분. */
    static long snoozeMs(long now, long blockEnd) {
        if (blockEnd <= now) return OPEN_ENDED_MS;
        return Math.max(60_000L, Math.min(blockEnd - now + 5_000L, MAX_SNOOZE_MS));
    }
}
