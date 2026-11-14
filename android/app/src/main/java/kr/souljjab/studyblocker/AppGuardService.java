package kr.souljjab.studyblocker;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/** 차단 중에 차단 목록의 앱이나 삼성 보안 폴더가 화면에 뜨면 홈으로 보내고 차단 화면을 띄운다. */
public class AppGuardService extends AccessibilityService {
    /** 패키지 → 본체에 설치돼 있는지. 매번 묻지 않으려고 잠시 기억한다. */
    private final Map<String, Boolean> installedCache = new HashMap<>();
    private long cacheAt;
    /** 설치된 앱을 전부 볼 수 있는지(못 보면 '본체에 없는 앱' 판단을 쓰지 않는다). */
    private Boolean seesAllPackages;
    /** 빡집중 중에도 열어 두는 시스템 패키지(홈·키보드·전화 등). */
    private java.util.Set<String> hardSystem;
    private long hardSystemAt;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cs = e.getPackageName();
        if (cs == null || !Store.shouldBlock(this)) return;
        // 예약 시각에 백그라운드 시작이 막혔다면 여기서 웹 차단(VPN)을 다시 켠다.
        ScheduleAlarm.ensureBlocking(this);
        String pkg = cs.toString();
        if (Store.hardActive(this)) {
            // 빡집중: 고른 앱과 시스템 화면 말고는 전부 막는다.
            if (!HardMode.allowed(pkg, getPackageName(), Store.hardApps(this), hardSystem())) bounce(labelOf(pkg));
            return;
        }
        boolean sfBlock = Store.secureFolderBlock(this);
        int v = SecureFolder.verdict(pkg, getPackageName(), Store.apps(this), sfBlock,
                !sfBlock || installedHere(pkg));
        if (v == SecureFolder.PASS) return;
        bounce(v == SecureFolder.SECURE_FOLDER ? SecureFolder.LABEL : labelOf(pkg));
    }

    private String labelOf(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** 홈으로 보내고 차단 화면을 띄운다. */
    private void bounce(String label) {
        performGlobalAction(GLOBAL_ACTION_HOME);
        startActivity(new Intent(this, BlockedActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockedActivity.EXTRA_LABEL, label));
    }

    private java.util.Set<String> hardSystem() {
        long now = System.currentTimeMillis();
        if (hardSystem == null || now - hardSystemAt > 60_000) { // 키보드·런처가 바뀌는 것을 반영
            hardSystem = HardMode.systemAllowed(this);
            hardSystemAt = now;
        }
        return hardSystem;
    }

    private boolean installedHere(String pkg) {
        PackageManager pm = getPackageManager();
        if (seesAllPackages == null) {
            // 시스템 UI가 안 보인다면 앱 목록 조회가 제한된 것이니, 잘못 막지 않도록 판단을 건너뛴다.
            seesAllPackages = SecureFolder.installed(pm, "com.android.systemui");
        }
        if (!seesAllPackages) return true;
        long now = System.currentTimeMillis();
        if (now - cacheAt > 60_000) { // 앱 설치·삭제를 반영
            installedCache.clear();
            cacheAt = now;
        }
        Boolean hit = installedCache.get(pkg);
        if (hit == null) {
            hit = SecureFolder.installed(pm, pkg);
            installedCache.put(pkg, hit);
        }
        return hit;
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
