package kr.souljjab.studyblocker;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/** 차단 중에 차단 목록의 앱이 화면에 뜨면 홈으로 보내고 차단 화면을 띄운다. */
public class AppGuardService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !Store.shouldBlock(this)) return;
        // 예약 시각에 백그라운드 시작이 막혔다면 여기서 웹 차단(VPN)을 다시 켠다.
        ScheduleAlarm.ensureBlocking(this);
        if (Store.apps(this).contains(pkg.toString())) {
            String label = pkg.toString();
            try {
                label = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(label, 0)).toString();
            } catch (Exception ignored) {
            }
            performGlobalAction(GLOBAL_ACTION_HOME);
            startActivity(new Intent(this, BlockedActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(BlockedActivity.EXTRA_LABEL, label));
        }
    }

    @Override
    public void onInterrupt() {}

    static boolean isEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(s)) return false;
        String me = new ComponentName(c, AppGuardService.class).flattenToString();
        for (String x : s.split(":")) if (x.equalsIgnoreCase(me)) return true;
        return false;
    }
}
