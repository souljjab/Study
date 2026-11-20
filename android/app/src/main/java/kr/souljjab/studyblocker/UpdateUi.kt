package kr.souljjab.studyblocker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 화면에 보여 줄 업데이트 상태(Updater의 값을 한 번에 읽어 둔다). */
internal data class UpdateState(
    val current: String = BuildConfig.VERSION_NAME,
    val state: String = "idle",
    val latest: String = "",
    val notes: String = "",
    val error: String = "",
    val checkedAt: Long = 0,
) {
    companion object {
        fun read() = UpdateState(
            state = Updater.state, latest = Updater.latestName, notes = Updater.notes,
            error = Updater.error, checkedAt = Updater.checkedAt,
        )
    }
}

internal fun updateStatus(u: UpdateState): String = when (u.state) {
    "checking" -> "새 버전이 있는지 확인하는 중…"
    "downloading" -> "새 버전 ${u.latest} 내려받는 중…"
    "latest" -> "최신 버전이에요" + if (u.checkedAt > 0) " · ${hhmm(u.checkedAt)}에 확인" else ""
    "ready" -> "새 버전 ${u.latest}을 받아 뒀어요"
    "error" -> "확인하지 못했어요: ${u.error}"
    else -> "앱을 열 때 6시간마다 알아서 확인해요"
}

/** 새 버전을 받아 뒀을 때 화면 위쪽에 뜨는 띠. */
@Composable
internal fun UpdateBanner(u: UpdateState, pal: Palette, onInstall: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("새 버전 ${u.latest} 준비됨", color = pal.fg, fontWeight = FontWeight.Black)
            if (u.notes.isNotEmpty()) Text(u.notes.lineSequence().first(), color = pal.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .background(pal.accent)
                .clickable(onClick = onInstall)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("설치", color = pal.onAccent, fontWeight = FontWeight.Black)
        }
    }
}

/** 기록 화면 맨 아래의 앱 정보·업데이트 카드. */
@Composable
internal fun UpdateCard(u: UpdateState, hard: Boolean, err: String, pal: Palette, onCheck: () -> Unit, onInstall: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, pal.fg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("앱 정보", pal)
        Text("현재 버전 ${u.current}", color = pal.fg, fontWeight = FontWeight.Black)
        Text(updateStatus(u), color = pal.muted, fontSize = 13.sp)
        if (u.state == "ready" && u.notes.isNotEmpty()) Text("바뀐 점: ${u.notes}", color = pal.fg, fontSize = 13.sp)
        when {
            u.state == "ready" && hard -> Text("빡집중이 끝난 뒤 설치할 수 있어요.", color = pal.fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            u.state == "ready" -> SharpButton("${u.latest} 설치", pal, onClick = onInstall)
            else -> SharpButton(
                "업데이트 확인", pal, filled = false,
                enabled = u.state != "checking" && u.state != "downloading", onClick = onCheck,
            )
        }
        if (err.isNotEmpty()) Text(err, color = pal.fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("설치할 때마다 안드로이드가 확인을 물어요. 같은 키로 서명된 버전만 설치돼요.", color = pal.muted, fontSize = 11.sp)
    }
}
