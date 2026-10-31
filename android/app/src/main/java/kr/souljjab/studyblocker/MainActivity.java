package kr.souljjab.studyblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private EditText minutes;
    private EditText site;
    private Button manualBtn;
    private LinearLayout list;
    private Runnable pending;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("📚 공부 집중 차단기");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        minutes = new EditText(this);
        minutes.setHint("집중할 시간(분)");
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(minutes);

        Button focus = new Button(this);
        focus.setText("집중 모드 시작 (시간 내 해제 불가)");
        focus.setOnClickListener(v -> startFocus());
        root.addView(focus);

        manualBtn = new Button(this);
        manualBtn.setOnClickListener(v -> toggleManual());
        root.addView(manualBtn);

        Button appsBtn = new Button(this);
        appsBtn.setText("차단할 앱 선택");
        appsBtn.setOnClickListener(v -> pickApps());
        root.addView(appsBtn);

        Button accBtn = new Button(this);
        accBtn.setText("앱 차단 켜기 (접근성 권한 설정 열기)");
        accBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accBtn);

        TextView hdr = new TextView(this);
        hdr.setText("\n차단 목록 (항목을 누르면 삭제)");
        hdr.setTextSize(16);
        root.addView(hdr);

        site = new EditText(this);
        site.setHint("추가할 사이트 (예: example.com)");
        site.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(site);

        Button add = new Button(this);
        add.setText("사이트 추가");
        add.setOnClickListener(v -> addSite());
        root.addView(add);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        ScrollView sv = new ScrollView(this);
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 앱을 열 때 차단이 켜져 있어야 하는데 서비스가 죽어 있으면 되살린다.
        if (Store.shouldBlock(this) && VpnService.prepare(this) == null) {
            startService(new Intent(this, BlockVpnService.class));
        }
        renderList();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void refreshStatus() {
        if (Store.locked(this)) {
            long left = (Store.lockEnd(this) - System.currentTimeMillis()) / 1000;
            status.setText(String.format("🔒 집중 중  %02d:%02d:%02d\n딴짓 금지! 공부하자",
                    left / 3600, left / 60 % 60, left % 60));
        } else if (Store.manual(this)) {
            status.setText("🚫 차단 중 (수동)");
        } else {
            status.setText("차단 꺼짐");
        }
        if (!AppGuardService.isEnabled(this)) {
            status.append("\n⚠️ 앱 차단: 아래 '접근성 권한'에서 '공부 집중 차단기'를 켜야 유튜브·인스타 앱이 차단돼요");
        }
        manualBtn.setText(Store.manual(this) ? "수동 차단 해제" : "지금 바로 차단 (수동)");
        manualBtn.setEnabled(!Store.locked(this));
    }

    /** VPN 권한을 확인한 뒤 action을 실행한다. 처음에는 시스템 동의 창이 뜬다. */
    private void withVpn(Runnable action) {
        Intent i = VpnService.prepare(this);
        if (i == null) {
            action.run();
        } else {
            pending = action;
            startActivityForResult(i, REQ_VPN);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_VPN && res == RESULT_OK && pending != null) {
            pending.run();
        } else if (req == REQ_VPN) {
            Toast.makeText(this, "VPN 권한을 허용해야 차단할 수 있어요", Toast.LENGTH_LONG).show();
        }
        pending = null;
    }

    private void startFocus() {
        int n;
        try {
            n = Integer.parseInt(minutes.getText().toString().trim());
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n < 1 || n > 1440) {
            Toast.makeText(this, "1~1440 사이의 분을 입력하세요", Toast.LENGTH_SHORT).show();
            return;
        }
        final long end = System.currentTimeMillis() + n * 60_000L;
        withVpn(() -> {
            // 이미 진행 중인 집중 시간은 줄일 수 없다.
            Store.setLockEnd(this, Math.max(end, Store.lockEnd(this)));
            startService(new Intent(this, BlockVpnService.class));
            refreshStatus();
        });
    }

    private void toggleManual() {
        if (Store.manual(this)) {
            Store.setManual(this, false);
            startService(new Intent(this, BlockVpnService.class)); // 서비스가 스스로 정리
            refreshStatus();
        } else {
            withVpn(() -> {
                Store.setManual(this, true);
                startService(new Intent(this, BlockVpnService.class));
                refreshStatus();
            });
        }
    }

    private void pickApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<String> pkgs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkgs.contains(pkg)) continue;
            pkgs.add(pkg);
        }
        final Set<String> blocked = Store.apps(this);
        // 차단된 앱을 위로, 나머지는 이름순
        pkgs.sort((a, b) -> {
            int ba = blocked.contains(a) ? 0 : 1, bb = blocked.contains(b) ? 0 : 1;
            return ba != bb ? ba - bb : label(pm, a).compareToIgnoreCase(label(pm, b));
        });
        final boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            labels.add(label(pm, pkgs.get(i)));
            checked[i] = blocked.contains(pkgs.get(i));
        }
        final boolean locked = Store.locked(this);
        final AlertDialog[] dlg = new AlertDialog[1];
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle(locked ? "차단할 앱 (집중 중엔 추가만 가능)" : "차단할 앱")
                .setMultiChoiceItems(labels.toArray(new String[0]), checked, (d, which, on) -> {
                    if (locked && !on && blocked.contains(pkgs.get(which))) {
                        dlg[0].getListView().setItemChecked(which, true);
                    }
                    checked[which] = dlg[0].getListView().isItemChecked(which);
                })
                .setPositiveButton("저장", (d, w) -> {
                    Set<String> out = new TreeSet<>();
                    for (int i = 0; i < pkgs.size(); i++) if (checked[i]) out.add(pkgs.get(i));
                    // 목록에 안 보이는(미설치) 기본 앱은 유지한다.
                    for (String b : blocked) if (!pkgs.contains(b)) out.add(b);
                    Store.saveApps(this, out);
                })
                .setNegativeButton("취소", null)
                .create();
        dlg[0].show();
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void addSite() {
        String d = Store.normalize(site.getText().toString());
        if (d == null) {
            Toast.makeText(this, "올바른 주소가 아니에요", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> s = Store.sites(this);
        if (!s.contains(d)) {
            s.add(d);
            Store.saveSites(this, s);
        }
        site.setText("");
        renderList();
    }

    private void renderList() {
        list.removeAllViews();
        for (String s : Store.sites(this)) {
            TextView t = new TextView(this);
            t.setText("• " + s);
            t.setTextSize(16);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setOnClickListener((View v) -> {
                if (Store.locked(this)) {
                    Toast.makeText(this, "집중 중에는 삭제할 수 없어요", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<String> cur = Store.sites(this);
                cur.remove(s);
                Store.saveSites(this, cur);
                renderList();
            });
            list.addView(t);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
