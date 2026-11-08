package kr.souljjab.studyblocker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

import java.util.Calendar;

/**
 * 앱이 꺼져 있어도 예약 시각에 차단을 켜려고 정확한 알람을 건다.
 * 알람이 울리면 차단(VPN)을 시작하고 다음 알람을 다시 건다.
 */
public class ScheduleAlarm extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        ensureBlocking(ctx);
        arm(ctx);
    }

    /** 차단해야 하는데 VPN이 꺼져 있으면 켠다. */
    static void ensureBlocking(Context ctx) {
        if (!Store.shouldBlock(ctx) || BlockVpnService.active || VpnService.prepare(ctx) != null) return;
        try {
            ctx.startService(new Intent(ctx, BlockVpnService.class));
        } catch (RuntimeException ignored) {
            // 백그라운드 시작이 막히면 접근성 서비스나 앱을 열 때 다시 시도한다.
        }
    }

    /** 가장 가까운 예약 시작(또는 진행 중인 예약의 끝)에 알람을 건다. */
    static void arm(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, ScheduleAlarm.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Calendar now = Calendar.getInstance();
        long next = -1;
        long until = Store.unlockUntil(ctx);
        if (until > now.getTimeInMillis()) next = until + 1000; // 긴급 해제가 끝나면 다시 차단
        for (Schedule s : Store.schedules(ctx)) {
            long t = s.activeAt(now) ? s.activeEndMillis(now) + 1000 : s.nextStartMillis(now);
            if (t > 0 && (next < 0 || t < next)) next = t;
        }
        if (next < 0) {
            am.cancel(pi);
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }
}
