package kr.souljjab.studyblocker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 빡집중 모드: 고른 앱(최대 3개)만 쓸 수 있고, 끝날 때까지 어떤 방법으로도 해제할 수 없다.
 * 전화·홈 화면·키보드·시스템 화면은 휴대폰을 쓸 수 있게(특히 긴급 통화) 항상 열어 둔다.
 */
public final class HardMode {
    static final int MAX_APPS = 3;
    static final int MAX_MINUTES = 12 * 60; // 잘못 입력해 며칠씩 잠기지 않도록 한 번에 12시간까지

    /** 시스템이 돌아가는 데 필요한 화면과 전화. 설정·앱 설치/삭제 화면은 일부러 넣지 않는다. */
    private static final String[] ALWAYS = {
            "android", "com.android.systemui", "com.android.intentresolver",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.samsung.android.biometrics.app.setting",
            // 전화: 긴급 통화와 걸려 오는 전화는 막지 않는다
            "com.android.phone", "com.android.server.telecom", "com.android.incallui", "com.android.dialer",
            "com.android.emergency", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.samsung.android.incallui",
    };
    private static final String SETTINGS = "com.android.settings";

    private HardMode() {}

    /** 빡집중 중에 pkg의 화면을 그대로 둘지. */
    static boolean allowed(String pkg, String self, Set<String> chosen, Set<String> system) {
        return pkg.equals(self) || chosen.contains(pkg) || system.contains(pkg);
    }

    /** 시작할 수 없으면 이유를, 괜찮으면 null을 돌려준다. */
    static String error(int minutes, int appCount, boolean accessibilityOn, boolean alreadyActive) {
        if (alreadyActive) return "이미 빡집중 모드예요.";
        if (!accessibilityOn) return "접근성 권한을 켜야 빡집중 모드를 쓸 수 있어요.";
        if (appCount > MAX_APPS) return "허용 앱은 3개까지 고를 수 있어요.";
        if (minutes < 1 || minutes > MAX_MINUTES) return "제한 시간은 1분~12시간 사이로 정하세요.";
        return null;
    }

    /** 항상 열어 두는 패키지: 고정 목록 + 이 기기의 홈 화면·키보드·기본 전화 앱. */
    static Set<String> systemAllowed(Context c) {
        Set<String> s = new HashSet<>(Arrays.asList(ALWAYS));
        PackageManager pm = c.getPackageManager();
        // 홈 화면으로 돌려보내야 하므로 런처는 반드시 허용한다(설정 앱의 대체 홈은 제외).
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            if (ri.activityInfo != null && !SETTINGS.equals(ri.activityInfo.packageName)) {
                s.add(ri.activityInfo.packageName);
            }
        }
        try {
            String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName cn = ime == null ? null : ComponentName.unflattenFromString(ime);
            if (cn != null) s.add(cn.getPackageName());
            InputMethodManager imm = c.getSystemService(InputMethodManager.class);
            if (imm != null) for (InputMethodInfo i : imm.getEnabledInputMethodList()) s.add(i.getPackageName());
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            String dialer = tm == null ? null : tm.getDefaultDialerPackage();
            if (dialer != null) s.add(dialer);
        } catch (RuntimeException ignored) {
            // 일부 기기에서 조회가 막혀도 고정 목록과 런처는 남는다.
        }
        s.remove(SETTINGS);
        return s;
    }
}
