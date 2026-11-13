package kr.souljjab.studyblocker;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Set;

/**
 * 삼성 보안 폴더 차단. 보안 폴더는 별도 프로필이라 VPN(웹 차단)이 닿지 않으므로,
 * 차단 중에는 보안 폴더 화면 자체를 접근성 서비스로 막는다.
 */
public final class SecureFolder {
    static final String PKG = "com.samsung.knox.securefolder";
    static final String LABEL = "보안 폴더";

    static final int PASS = 0;
    static final int BLOCKED_APP = 1;
    static final int SECURE_FOLDER = 2;

    private SecureFolder() {}

    /**
     * 화면에 뜬 앱을 어떻게 할지 정한다.
     *
     * @param installedHere 이 프로필(본체)에 설치된 앱인지. 판단할 수 없으면 true.
     *                      본체에 없는 앱의 화면이 보인다면 보안 폴더 같은 다른 프로필의 앱이다.
     */
    static int verdict(String pkg, String self, Set<String> blockedApps, boolean sfBlock, boolean installedHere) {
        if (pkg.equals(self)) return PASS;
        if (sfBlock && (PKG.equals(pkg) || !installedHere)) return SECURE_FOLDER;
        if (blockedApps.contains(pkg)) return BLOCKED_APP;
        return PASS;
    }

    /** 설정에 스위치를 보여 줄지: 삼성 기기이거나 보안 폴더 앱이 있을 때. */
    static boolean available(Context c) {
        if ("samsung".equalsIgnoreCase(Build.MANUFACTURER)) return true;
        return installed(c.getPackageManager(), PKG);
    }

    static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
