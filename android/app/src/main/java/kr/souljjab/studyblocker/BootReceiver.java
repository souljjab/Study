package kr.souljjab.studyblocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** 재부팅 후에도 집중 모드가 남아 있으면 차단을 다시 시작한다. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        ScheduleAlarm.arm(ctx);
        if (!Store.shouldBlock(ctx) || VpnService.prepare(ctx) != null) return;
        try {
            ctx.startService(new Intent(ctx, BlockVpnService.class));
        } catch (RuntimeException ignored) {
            // 백그라운드 시작이 거부되면 앱을 열 때 다시 시작된다.
        }
    }
}
