package kr.souljjab.studyblocker

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 앱이 실행 중에 받아야 하는 권한 상태. */
internal data class Perms(val vpn: Boolean, val acc: Boolean, val battery: Boolean) {
    val requiredDone: Boolean get() = vpn && acc
    val requiredCount: Int get() = listOf(vpn, acc).count { it }
}

internal fun readPerms(c: Context): Perms {
    val pm = c.getSystemService(PowerManager::class.java)
    return Perms(
        vpn = VpnService.prepare(c) == null,
        acc = AppGuardService.isEnabled(c),
        battery = pm?.isIgnoringBatteryOptimizations(c.packageName) == true,
    )
}

/** 안드로이드 13부터 파일로 설치한 앱은 접근성 권한이 '제한된 설정'으로 잠긴다. */
internal val restrictedSettingsOs: Boolean get() = Build.VERSION.SDK_INT >= 33

private fun Context.tryStart(vararg intents: Intent) {
    for (i in intents) {
        try {
            startActivity(i)
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

internal fun openAccessibilitySettings(c: Context) {
    val me = ComponentName(c, AppGuardService::class.java).flattenToString()
    // 일부 기기는 이 값으로 우리 앱 항목을 강조해 준다.
    c.tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(":settings:fragment_args_key", me))
}

internal fun openAppInfo(c: Context) {
    c.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
}

internal fun requestBatteryExemption(c: Context) {
    c.tryStart(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
}

internal fun openVpnSettings(c: Context) {
    c.tryStart(Intent(Settings.ACTION_VPN_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
}

@Composable
internal fun PermissionScreen(
    p: Perms,
    pal: Palette,
    showRestrictedHelp: Boolean,
    onVpn: () -> Unit,
    onAcc: () -> Unit,
    onAppInfo: () -> Unit,
    onBattery: () -> Unit,
    onAlwaysOn: () -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(pal.bg)
            .systemBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Label("Permissions", pal)
                Text("권한 설정", color = pal.fg, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(
                    "필수 권한 ${p.requiredCount}/2 허용됨",
                    color = if (p.requiredDone) pal.fg else pal.muted,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item {
            PermCard(
                no = "01", title = "VPN 연결", tag = "필수", pal = pal,
                desc = "웹사이트 차단에 씁니다. 휴대폰 안에서 차단 목록의 주소 요청만 걸러내고, " +
                    "데이터를 외부 서버로 보내지 않습니다.",
                granted = p.vpn, action = "허용", onClick = onVpn,
            )
        }
        item {
            PermCard(
                no = "02", title = "접근성", tag = "필수", pal = pal,
                desc = "유튜브·인스타 같은 앱 차단에 씁니다. 어떤 앱이 열렸는지만 확인하고 " +
                    "화면 내용은 읽지 않습니다. 설정에서 '공부 집중 차단기'를 찾아 켜세요.",
                granted = p.acc, action = "설정 열기", onClick = onAcc,
            ) {
                if (showRestrictedHelp && !p.acc) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(2.dp, pal.muted)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("스위치가 회색으로 잠겨 있다면", color = pal.fg, fontWeight = FontWeight.Black)
                        Text(
                            "1. 아래 '앱 정보 열기'\n" +
                                "2. 오른쪽 위 ⋮ → '제한된 설정 허용'\n" +
                                "3. 다시 '설정 열기'에서 켜기",
                            color = pal.fg,
                            fontSize = 14.sp,
                        )
                        SharpButton("앱 정보 열기", pal, filled = false, onClick = onAppInfo)
                    }
                }
            }
        }
        item {
            PermCard(
                no = "03", title = "배터리 최적화 제외", tag = "권장", pal = pal,
                desc = "휴대폰이 절전하면서 차단을 몰래 꺼버리지 않게 합니다. " +
                    "삼성·샤오미 등에서 특히 필요합니다.",
                granted = p.battery, action = "허용", onClick = onBattery,
            )
        }
        item {
            PermCard(
                no = "04", title = "항상 켜진 VPN", tag = "선택", pal = pal,
                desc = "VPN 설정에서 '공부 집중 차단기' 옆 ⚙ → '항상 켜진 VPN'을 켜면 " +
                    "재부팅하거나 앱이 종료돼도 차단이 자동으로 다시 켜집니다.",
                granted = null, action = "VPN 설정 열기", onClick = onAlwaysOn,
            )
        }
        item {
            Text(
                "설치할 때 자동으로 허용되는 권한: 인터넷(차단하지 않은 주소 전달), " +
                    "부팅 완료 알림(재부팅 후 집중 모드 이어가기), 설치된 앱 목록 보기(차단할 앱 고르기)",
                color = pal.muted,
                fontSize = 12.sp,
            )
        }
        item {
            SharpButton(
                if (p.requiredDone) "완료" else "나중에 하기",
                pal,
                filled = p.requiredDone,
                onClick = onDone,
            )
        }
    }
}

/** granted가 null이면 상태를 알 수 없는(확인 불가) 항목이다. */
@Composable
private fun PermCard(
    no: String,
    title: String,
    tag: String,
    desc: String,
    granted: Boolean?,
    action: String,
    pal: Palette,
    onClick: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(no, color = pal.muted, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.width(10.dp))
            Text(title, color = pal.fg, fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            val required = tag == "필수"
            Box(
                Modifier
                    .background(if (required) pal.fg else Color.Transparent)
                    .border(2.dp, pal.fg)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(tag, color = if (required) pal.bg else pal.fg, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
        Text(desc, color = pal.fg, fontSize = 14.sp)
        if (granted == true) {
            Text("✓ 허용됨", color = pal.fg, fontWeight = FontWeight.Black)
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(pal.accent)
                    .clickable(onClick = onClick)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(action, color = pal.onAccent, fontWeight = FontWeight.Black)
            }
        }
        extra()
    }
}

/** 메인 화면 상단: 필수 권한이 빠졌을 때만 보인다. */
@Composable
internal fun PermBanner(p: Perms, pal: Palette, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("필수 권한 ${p.requiredCount}/2 허용됨", color = pal.fg, fontWeight = FontWeight.Black)
            Text("권한이 없으면 차단이 동작하지 않아요", color = pal.muted, fontSize = 12.sp)
        }
        Box(
            Modifier
                .background(pal.accent)
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("권한 설정", color = pal.onAccent, fontWeight = FontWeight.Black)
        }
    }
}
