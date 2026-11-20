package kr.souljjab.studyblocker;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 자동 업데이트: 공개 저장소의 update.json을 확인해 새 APK를 내려받고 지문(SHA-256)을 확인해 둔다.
 * 다른 경로로 설치한 앱은 안드로이드가 확인 없이 업데이트하지 못하게 하므로, 설치는 한 번 탭해야 한다.
 * (설치할 APK가 같은 키로 서명돼 있지 않으면 안드로이드가 설치를 거부한다.)
 */
public final class Updater {
    static final String MANIFEST_URL = "https://raw.githubusercontent.com/souljjab/Study/HEAD/dist/update.json";
    static final long CHECK_EVERY_MS = 6 * 3600_000L;
    private static final String[] HOSTS = {"raw.githubusercontent.com", "github.com", "objects.githubusercontent.com"};

    /** 화면에 보여 줄 상태. */
    static volatile String state = "idle"; // idle | checking | latest | downloading | ready | error
    static volatile String latestName = "";
    static volatile String notes = "";
    static volatile String error = "";
    static volatile long checkedAt;
    private static volatile File ready;

    private Updater() {}

    static final class Release {
        int versionCode;
        String versionName, url, sha256, notes;
        long size;
    }

    static boolean allowedUrl(String raw) {
        try {
            URL u = new URL(raw);
            if (!"https".equals(u.getProtocol())) return false;
            for (String h : HOSTS) if (h.equals(u.getHost())) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** update.json의 android 항목을 읽는다. 없거나 잘못됐으면 null. */
    static Release parse(String json) {
        try {
            JSONObject a = new JSONObject(json).optJSONObject("android");
            if (a == null) return null;
            Release r = new Release();
            r.versionCode = a.getInt("versionCode");
            r.versionName = a.optString("versionName", String.valueOf(r.versionCode));
            r.url = a.getString("url");
            r.sha256 = a.getString("sha256").trim().toLowerCase(java.util.Locale.ROOT);
            r.size = a.optLong("size", 0);
            r.notes = a.optString("notes", "");
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    static String sha256Hex(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest()) b.append(String.format(java.util.Locale.ROOT, "%02x", x));
        return b.toString();
    }

    static File readyFile() {
        File f = ready;
        return f != null && f.exists() && "ready".equals(state) ? f : null;
    }

    /** 마지막 확인에서 6시간이 지났으면 뒤에서 확인한다(앱을 열 때 부른다). */
    static void maybeCheck(Context c) {
        long last = Math.max(checkedAt, Store.lastUpdateCheck(c));
        if (System.currentTimeMillis() - last > CHECK_EVERY_MS) checkAsync(c);
    }

    static void checkAsync(Context c) {
        if ("checking".equals(state) || "downloading".equals(state)) return;
        Context app = c.getApplicationContext();
        state = "checking";
        error = "";
        new Thread(() -> {
            try {
                check(app);
            } catch (Exception e) {
                error = e.getMessage() == null ? "알 수 없는 오류" : e.getMessage();
                state = "error";
            }
        }, "update-check").start();
    }

    private static HttpURLConnection open(String raw) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(raw).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(60_000);
        con.setRequestProperty("Cache-Control", "no-cache");
        if (con.getResponseCode() != 200) throw new Exception("업데이트 서버 응답 오류(" + con.getResponseCode() + ")");
        return con;
    }

    private static void check(Context c) throws Exception {
        checkedAt = System.currentTimeMillis();
        Store.setLastUpdateCheck(c, checkedAt);
        String body;
        HttpURLConnection con = open(MANIFEST_URL);
        try (InputStream in = con.getInputStream()) {
            byte[] b = new byte[64 * 1024];
            int total = 0;
            for (int n; total < b.length && (n = in.read(b, total, b.length - total)) > 0; ) total += n;
            body = new String(b, 0, total, "UTF-8");
        } finally {
            con.disconnect();
        }
        Release r = parse(body);
        if (r == null) throw new Exception("업데이트 정보를 읽지 못했어요.");
        if (r.versionCode <= BuildConfig.VERSION_CODE) {
            state = "latest";
            return;
        }
        latestName = r.versionName;
        notes = r.notes;
        File dir = new File(c.getCacheDir(), "update");
        File apk = new File(dir, "StudyBlocker-" + r.versionCode + ".apk");
        if (apk.exists() && r.sha256.equals(hashOf(apk))) {
            ready = apk;
            state = "ready";
            return;
        }
        if (!allowedUrl(r.url)) throw new Exception("허용되지 않은 주소라 내려받지 않았어요.");
        if (r.sha256.length() != 64) throw new Exception("업데이트 정보에 파일 지문이 없어요.");
        state = "downloading";
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("저장 공간이 없어요.");
        File part = new File(dir, apk.getName() + ".part");
        HttpURLConnection dl = open(r.url);
        long limit = r.size > 0 ? r.size + 1 : 64L << 20;
        try (InputStream in = dl.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > limit) throw new Exception("내려받은 파일 크기가 맞지 않아요.");
                out.write(buf, 0, n);
            }
        } finally {
            dl.disconnect();
        }
        if (!r.sha256.equals(hashOf(part))) {
            part.delete();
            throw new Exception("내려받은 파일의 지문이 맞지 않아 버렸어요. 잠시 뒤 다시 시도해요.");
        }
        if (!part.renameTo(apk)) throw new Exception("내려받은 파일을 저장하지 못했어요.");
        ready = apk;
        state = "ready";
    }

    private static String hashOf(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return sha256Hex(in);
        }
    }

    /** 이 앱이 APK를 설치할 수 있는지(설정의 '출처를 알 수 없는 앱 설치' 허용). */
    static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static void openInstallPermission(Context c) {
        c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** 받아 둔 APK 설치를 시작한다. 안드로이드가 설치 확인 창을 띄운다. 실패하면 이유를 돌려준다. */
    static String install(Context c) {
        if (Store.hardActive(c)) return "빡집중이 끝난 뒤 설치할 수 있어요.";
        File apk = readyFile();
        if (apk == null) return "설치할 업데이트가 없어요.";
        if (!canInstall(c)) {
            openInstallPermission(c);
            return "이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 눌러 주세요.";
        }
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            p.setSize(apk.length());
            int id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    s.fsync(out);
                }
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, Result.class), flags);
                s.commit(done.getIntentSender());
            }
            return null;
        } catch (Exception e) {
            return "설치를 시작하지 못했어요: " + e.getMessage();
        }
    }

    /** 설치 결과를 받는다. 확인이 필요하면 안드로이드의 설치 확인 창을 띄운다. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33
                        ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                error = "설치하지 못했어요" + (msg == null ? "" : ": " + msg);
                state = "error";
            }
        }
    }
}
