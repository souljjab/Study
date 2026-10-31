package kr.souljjab.studyblocker;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/** 차단 중에 차단 목록의 앱이 화면에 뜨면 홈 화면으로 돌려보낸다. */
public class AppGuardService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !Store.shouldBlock(this)) return;
        if (Store.apps(this).contains(pkg.toString())) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            Toast.makeText(this, "📚 집중 중! 딴짓 금지", Toast.LENGTH_SHORT).show();
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
