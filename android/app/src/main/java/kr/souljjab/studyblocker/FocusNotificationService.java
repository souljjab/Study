package kr.souljjab.studyblocker;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

/**
 * 집중 중에는 알림을 받지 않게 한다.
 * - 방해 금지(알람만)를 켜서 소리·진동을 막고, 끝나면 원래 방해 금지 상태로 되돌린다.
 * - 들어온 알림은 지우지 않고 집중이 끝날 때까지 미뤄(스누즈) 두어, 끝나면 다시 나타난다.
 */
public class FocusNotificationService extends NotificationListenerService {
    private static final long CHECK_MS = 5_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean connected;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!connected) return;
            sync();
            handler.postDelayed(this, CHECK_MS);
        }
    };

    @Override
    public void onListenerConnected() {
        connected = true;
        handler.removeCallbacks(loop);
        handler.post(loop);
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        handler.removeCallbacks(loop);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (connected && Store.silenceNow(this)) hide(sbn, System.currentTimeMillis());
    }

    /** 지금 상태에 맞춰 방해 금지를 켜거나 되돌리고, 떠 있는 알림을 미룬다. */
    private void sync() {
        try {
            if (Store.silenceNow(this)) {
                if (!Store.dndByUs(this)) {
                    Store.setDndByUs(this, true, getCurrentInterruptionFilter());
                }
                if (getCurrentInterruptionFilter() != INTERRUPTION_FILTER_ALARMS) {
                    requestInterruptionFilter(INTERRUPTION_FILTER_ALARMS);
                }
                long now = System.currentTimeMillis();
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) for (StatusBarNotification s : active) hide(s, now);
            } else if (Store.dndByUs(this)) {
                int prev = Store.dndBefore(this);
                requestInterruptionFilter(prev == INTERRUPTION_FILTER_UNKNOWN ? INTERRUPTION_FILTER_ALL : prev);
                Store.setDndByUs(this, false, INTERRUPTION_FILTER_UNKNOWN);
            }
        } catch (RuntimeException ignored) {
            // 권한이 막 꺼진 직후 등에는 다음 확인 때 다시 시도한다.
        }
    }

    private void hide(StatusBarNotification s, long now) {
        if (!NotifyRules.shouldHide(s.getPackageName(), getPackageName(), s.isClearable(), s.getNotification().category)) return;
        try {
            snoozeNotification(s.getKey(), NotifyRules.snoozeMs(now, Store.blockEndMillis(this)));
        } catch (RuntimeException ignored) {
        }
    }

    static boolean isEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(s)) return false;
        String me = new ComponentName(c, FocusNotificationService.class).flattenToString();
        for (String x : s.split(":")) if (x.equalsIgnoreCase(me)) return true;
        return false;
    }
}
