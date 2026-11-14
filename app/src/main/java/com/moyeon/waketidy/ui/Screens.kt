package com.moyeon.waketidy.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyeon.waketidy.AppController
import com.moyeon.waketidy.BuildConfig
import com.moyeon.waketidy.Tab
import com.moyeon.waketidy.TidyStatus
import com.moyeon.waketidy.UiState
import com.moyeon.waketidy.engine.Areas
import com.moyeon.waketidy.engine.GearState
import com.moyeon.waketidy.engine.HandoverBuilder
import com.moyeon.waketidy.engine.HandoverCard
import com.moyeon.waketidy.engine.LogbookCsv
import com.moyeon.waketidy.engine.Purpose
import com.moyeon.waketidy.engine.RestState
import com.moyeon.waketidy.engine.WakeMethod
import com.moyeon.waketidy.engine.WakeSchedule
import com.moyeon.waketidy.engine.PublicWarnings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WakeTidyApp(ui: UiState, c: AppController) {
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(ui, c)
        if (ui.scenarioRunning) ScenarioBanner(ui, c)
        if (ui.message.isNotBlank() && !ui.scenarioRunning) MessageBar(ui.message)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            NavRail(ui, c)
            Box(Modifier.weight(1f).fillMaxHeight().padding(32.dp)) {
                when {
                    ui.driving -> DrivingScreen(ui)
                    ui.rest !is RestState.Idle -> RestScreen(ui, c)
                    ui.exitVisible -> ExitScreen(ui, c)
                    else -> when (ui.tab) {
                        Tab.HOME -> HomeScreen(ui, c)
                        Tab.PROFILE -> ProfileScreen(ui, c)
                        Tab.LOGBOOK -> LogbookScreen(ui, c)
                        Tab.HANDOVER -> HandoverScreen(ui, c)
                        Tab.SDK -> SdkLogScreen(ui)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 공통 바

@Composable
private fun TopBar(ui: UiState, c: AppController) {
    Row(
        Modifier.fillMaxWidth().background(C.surface).padding(horizontal = 32.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        T("깨우고 정리", 40.sp, C.amber, bold = true)
        GearBadge(ui.gear)
        T("연속 운전 ${minutes(ui.drivingMs)} / 기준 ${ui.limitMinutes}분", 28.sp, if (ui.drivingMs >= ui.limitMinutes * 60_000L) C.amber else C.muted)
        Spacer(Modifier.weight(1f))
        ui.driver?.let { T("운전자 ${it.display}", 26.sp, C.muted) }
        T(if (ui.sdkReady) "Vehicle SDK ${BuildConfig.VEHICLE_SDK_VERSION} 연결" else "Vehicle SDK 연결 실패", 22.sp, if (ui.sdkReady) C.mint else C.red)
        if (ui.gearSupported == false) T("기어(AAOS) 미지원", 22.sp, C.red)
        BigButton(
            if (ui.scenarioRunning) "■ 시나리오 멈춤" else "▶ 시연 시나리오 재생",
            c::toggleScenario,
            color = if (ui.scenarioRunning) C.red else C.blue,
            enabled = ui.scenarioRunning || !ui.driving,
            size = 26.sp,
        )
    }
}

@Composable
private fun GearBadge(g: GearState) {
    val (label, color) = when (g) {
        GearState.PARK -> "P" to C.mint
        GearState.DRIVE -> "D" to C.amber
        GearState.REVERSE -> "R" to C.red
        GearState.NEUTRAL -> "N" to C.blue
        GearState.UNKNOWN -> "?" to C.muted
    }
    Box(Modifier.size(72.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        T(label, 40.sp, Color(0xFF0B0F14), bold = true)
    }
}

@Composable
private fun ScenarioBanner(ui: UiState, c: AppController) {
    Row(
        Modifier.fillMaxWidth().background(C.amber).padding(horizontal = 32.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T("▶ 시나리오 재생 중", 30.sp, Color(0xFF1B1300), bold = true)
        Spacer(Modifier.width(24.dp))
        T("화면의 차 신호는 가상 값입니다 · 창·등·잠금·공조 쓰기는 실제 SDK 호출", 24.sp, Color(0xFF3A2A00))
        Spacer(Modifier.weight(1f))
        T(ui.scenarioCaption, 26.sp, Color(0xFF1B1300), bold = true)
    }
}

@Composable
private fun MessageBar(msg: String) {
    Box(Modifier.fillMaxWidth().background(C.surface2).padding(horizontal = 32.dp, vertical = 12.dp)) {
        T(msg, 24.sp, C.text)
    }
}

@Composable
private fun NavRail(ui: UiState, c: AppController) {
    val locked = ui.driving || ui.rest !is RestState.Idle
    Column(
        Modifier.width(320.dp).fillMaxHeight().background(C.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Tab.entries.forEach { t ->
            val selected = ui.tab == t && !ui.exitVisible && !locked
            Box(
                Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (selected) C.surface2 else Color.Transparent)
                    .clickable(enabled = !locked) { c.closeExitCard(); c.selectTab(t) }
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) { T(t.label, 30.sp, if (locked) C.surface2 else if (selected) C.amber else C.text, bold = selected) }
        }
        Spacer(Modifier.weight(1f))
        if (locked && ui.driving) T("주행 중에는 화면 조작을 잠급니다", 22.sp, C.muted)
    }
}

// ---------------------------------------------------------------------------- 주행 중

@Composable
private fun DrivingScreen(ui: UiState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        T("운전 중", 72.sp, C.text, bold = true)
        Spacer(Modifier.height(24.dp))
        T("연속 운전 ${minutes(ui.drivingMs)}", 56.sp, C.amber, bold = true)
        Spacer(Modifier.height(40.dp))
        if (ui.drivingMs >= ui.limitMinutes * 60_000L) {
            T("${ui.limitMinutes}분이 넘었어요. 다음에 P단에 세우면 쉼을 제안할게요.", 36.sp, C.amber)
        } else {
            T("주행 중에는 아무것도 누르지 않아도 됩니다.", 32.sp, C.muted)
        }
        if (ui.tripActive) {
            Spacer(Modifier.height(24.dp))
            T("운행기록: 출발 계기판 ${ui.tripStartKm?.let { km(it) } ?: "-"} km 기록됨", 28.sp, C.muted)
        }
    }
}

// ---------------------------------------------------------------------------- 장면 1: 쉴 때

@Composable
private fun RestScreen(ui: UiState, c: AppController) {
    when (val r = ui.rest) {
        is RestState.Suggested -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            T("${r.drivingMinutes}분째 운전하셨어요", 64.sp, C.amber, bold = true)
            T("잠깐 쉬어 갈까요? 몇 분 쉴지 고르면 그 시간 뒤에 깨워 드릴게요.", 34.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                listOf(5, 10, 15).forEach { m ->
                    BigButton("${m}분", { c.chooseRest(m) }, Modifier.width(300.dp).height(180.dp), size = 56.sp)
                }
            }
            T("깨우는 방식: ${methodsText(ui.methods)}  (깨우는 방식 탭에서 바꿀 수 있어요)", 28.sp, C.muted)
            BigButton("괜찮아요, 그냥 갈게요", c::dismissRest, color = C.surface2, textColor = C.text, size = 28.sp)
        }
        is RestState.Resting -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            T("쉬는 중", 48.sp, C.muted)
            T(clock((r.endMs - ui.nowMs).coerceAtLeast(0)), 200.sp, C.text, bold = true)
            T("${r.minutes}분 뒤 ${methodsText(ui.methods)}(으)로 단계적으로 깨워 드릴게요", 34.sp, C.muted)
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                BigButton("지금 깨우기", c::wakeNow, color = C.surface2, textColor = C.text)
                BigButton("쉼 취소", c::cancelRest, color = C.surface2, textColor = C.text)
            }
        }
        is RestState.Waking -> {
            val bg by animateColorAsState(
                when (r.level) { 1 -> Color(0xFF3A2F12); 2 -> Color(0xFF7A5A12); else -> Color(0xFFC98A0E) },
                label = "wakeBg",
            )
            Column(
                Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)).background(bg).padding(48.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                T("일어날 시간이에요", 80.sp, C.text, bold = true)
                Spacer(Modifier.height(16.dp))
                T("깨우기 ${r.level} / ${WakeSchedule.MAX_LEVEL}단계 · ${WakeSchedule.STAGE_INTERVAL_SEC}초마다 조금씩 세져요", 34.sp)
                T(stageText(ui.methods, r.level), 30.sp, C.text)
                Spacer(Modifier.height(56.dp))
                BigButton("깼어요", c::awake, Modifier.width(520.dp).height(200.dp), color = C.mint, size = 72.sp)
            }
        }
        is RestState.Recovering -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            T("2분만 몸을 깨우고 출발해요", 60.sp, C.mint, bold = true)
            T(clock((r.endMs - ui.nowMs).coerceAtLeast(0)), 140.sp, C.text, bold = true)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                listOf("창문을 조금 열어 바깥 공기", "물 한 모금", "어깨·목 천천히 돌리기").forEach {
                    Card(Modifier.width(420.dp), C.surface2) { T(it, 30.sp) }
                }
            }
            T("깨우기에 쓴 무드등·바람은 원래 값으로, 독서등·열선·통풍은 껐어요.", 26.sp, C.muted)
            BigButton("바로 출발", c::skipRecovery, color = C.surface2, textColor = C.text)
        }
        RestState.Idle -> Unit
    }
}

// ---------------------------------------------------------------------------- 장면 2: 내릴 때

@Composable
private fun ExitScreen(ui: UiState, c: AppController) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            T("내리기 전에 한 장", 56.sp, C.mint, bold = true)
            if (ui.tidyRows.isEmpty()) T("정리할 것이 없어요. 창·독서등·잠금 모두 괜찮아요.", 32.sp)
            ui.tidyRows.forEach { row ->
                val (mark, color) = when {
                    !row.item.actionable -> "!" to C.amber
                    row.status == TidyStatus.DONE -> "✓" to C.mint
                    row.status == TidyStatus.FAILED -> "×" to C.red
                    row.status == TidyStatus.RUNNING -> "…" to C.blue
                    else -> "·" to C.text
                }
                Card(color = C.surface) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(mark, 44.sp, color, bold = true, modifier = Modifier.width(64.dp))
                        Column {
                            T(row.item.label, 34.sp, C.text, bold = !row.item.actionable)
                            if (row.detail.isNotBlank()) T(row.detail, 22.sp, if (row.status == TidyStatus.FAILED) C.red else C.muted)
                        }
                    }
                }
            }
            val actionable = ui.tidyRows.any { it.item.actionable && it.status != TidyStatus.DONE }
            BigButton(if (actionable) "한 번에 정리" else "정리 끝", c::tidyAll, Modifier.fillMaxWidth().height(140.dp), color = C.mint, enabled = actionable, size = 36.sp)
            BigButton("닫기", c::closeExitCard, color = C.surface2, textColor = C.text, size = 26.sp)
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PurposePanel(ui, c)
        }
    }
}

@Composable
private fun PurposePanel(ui: UiState, c: AppController) {
    val p = ui.pendingTrip
    Card {
        T("어디 다녀오셨어요?", 44.sp, C.amber, bold = true)
        val done = ui.lastEntry
        if (p == null && done != null) {
            T("✓ 운행기록부에 적었어요", 36.sp, C.mint, bold = true)
            T("${LogbookCsv.dateWithDay(done.date)} · ${done.driver.display}", 26.sp, C.muted)
            T("계기판 ${km(done.startKm)} → ${km(done.endKm)} km · ${km(done.distanceKm)} km", 30.sp)
            T(done.note, 30.sp, C.text, bold = true)
            return@Card
        }
        if (p == null) {
            T("이번 주행의 시작 거리를 앱이 보지 못해 운행기록부 줄은 만들지 않아요.", 26.sp, C.muted)
            return@Card
        }
        T("${p.start.driver.display}", 28.sp, C.muted)
        T("계기판 ${km(p.start.startKm)} → ${km(p.endKm)} km", 32.sp)
        T("주행거리 ${km(p.distanceKm)} km · ${p.minutes}분", 36.sp, C.text, bold = true)
        var memo by remember { mutableStateOf("") }
        OutlinedTextField(
            value = memo, onValueChange = { memo = it.take(30) },
            label = { T("방문처 메모(선택)", 20.sp, C.muted) },
            textStyle = TextStyle(fontSize = 28.sp, color = C.text),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.amber, unfocusedBorderColor = C.surface2),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Purpose.entries.forEach { pur ->
                BigButton(pur.label, { c.choosePurpose(pur, memo) }, Modifier.weight(1f), color = if (pur == Purpose.PERSONAL) C.surface2 else C.amber,
                    textColor = if (pur == Purpose.PERSONAL) C.text else Color(0xFF14110A), size = 30.sp)
            }
        }
        if (ui.recentPurposes.isNotEmpty()) {
            T("최근", 22.sp, C.muted)
            ui.recentPurposes.forEach { (pur, m) ->
                BigButton("${pur.label} · $m", { c.choosePurpose(pur, m) }, Modifier.fillMaxWidth(), color = C.surface2, textColor = C.text, size = 26.sp)
            }
        }
        T("위치·경로는 저장하지 않아요. 계기판 거리와 목적만 적어요.", 22.sp, C.muted)
    }
}

// ---------------------------------------------------------------------------- 탭: 지금

@Composable
private fun HomeScreen(ui: UiState, c: AppController) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            T(if (ui.gear == GearState.PARK) "주차 중" else "기어 정보를 기다리는 중", 56.sp, C.text, bold = true)
            Card {
                T("쉴 때", 36.sp, C.amber, bold = true)
                T("연속 ${ui.limitMinutes}분 운전 뒤 P단에 서면 쉼을 제안하고, 고른 시간 뒤 ${methodsText(ui.methods)}(으)로 깨워요.", 28.sp)
            }
            Card {
                T("내릴 때", 36.sp, C.mint, bold = true)
                T("P단에서 운전석 문을 열면 창문·독서등·뒷좌석·잠금을 한 장으로 보여 주고 한 번에 정리해요.", 28.sp)
            }
            Card {
                T("누가 타나요?", 36.sp, C.blue, bold = true)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ui.drivers.forEachIndexed { i, d ->
                        BigButton(d.name, { c.selectDriver(i) }, Modifier.weight(1f),
                            color = if (i == ui.driverIndex) C.blue else C.surface2,
                            textColor = if (i == ui.driverIndex) Color(0xFF06121A) else C.text, size = 28.sp)
                    }
                }
                ui.handover?.let { T("다음 사람 카드: ${HandoverBuilder.headline(it)}", 24.sp, C.muted) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            LiveCarCard(ui)
        }
    }
}

@Composable
private fun LiveCarCard(ui: UiState) {
    val s = ui.snapshot
    Card {
        T(if (ui.scenarioRunning) "지금 차 상태(가상)" else "지금 차 상태(SDK)", 36.sp, C.text, bold = true)
        Kv("운전석 문", when (s.driverDoorPosition) { null -> "읽는 중/미지원"; 0 -> "닫힘"; else -> "열림" })
        Kv("문 잠금", when (s.driverDoorLocked) { null -> "읽는 중/미지원"; true -> "잠김"; false -> "열림" })
        Kv("창문", Areas.CABIN.joinToString("  ") { a -> "${Areas.label(a)} ${s.windows[a]?.let { if (it > 0) "열림" else "닫힘" } ?: "-"}" })
        Kv("독서등", Areas.CABIN.joinToString("  ") { a -> "${Areas.label(a)} ${s.readingLights[a]?.let { if (it) "켜짐" else "꺼짐" } ?: "-"}" })
        Kv("뒷좌석", Areas.REAR.joinToString("  ") { a -> "${Areas.label(a)} ${s.rearOccupied[a]?.let { if (it) "착석" else "비어 있음" } ?: "-"}" })
        Kv("계기판", s.odometerKm?.let { "${km(it)} km" } ?: "-")
        Kv("연료 / 배터리", "${s.fuelPercent?.let { "$it%" } ?: "-"} / ${s.evSocPercent?.let { "$it%" } ?: "-"}")
        Kv("경고(공개 신호)", s.activeWarnings.joinToString { PublicWarnings.label(it) }.ifBlank { "없음" })
    }
}

@Composable
private fun Kv(k: String, v: String) = Row {
    T(k, 24.sp, C.muted, modifier = Modifier.width(200.dp))
    T(v, 24.sp)
}

// ---------------------------------------------------------------------------- 탭: 깨우는 방식(프로필)

@Composable
private fun ProfileScreen(ui: UiState, c: AppController) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            T("나를 깨우는 방식", 52.sp, C.amber, bold = true)
            T("고른 것만 써요. 30초마다 한 단계씩 세지고(3단계), 2단계부터 소리를 더해요.", 26.sp, C.muted)
            WakeMethod.entries.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowItems.forEach { m ->
                        val on = m in ui.methods
                        BigButton((if (on) "✓ " else "") + m.label, { c.toggleMethod(m) }, Modifier.weight(1f),
                            color = if (on) C.amber else C.surface2, textColor = if (on) Color(0xFF14110A) else C.text, size = 30.sp)
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            T("연속 운전 기준", 36.sp, C.text, bold = true)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(1 to "1분(시연)", 30 to "30분", 60 to "60분", 100 to "100분", 120 to "120분").forEach { (m, label) ->
                    BigButton(label, { c.setLimit(m) }, Modifier.weight(1f),
                        color = if (ui.limitMinutes == m) C.amber else C.surface2,
                        textColor = if (ui.limitMinutes == m) Color(0xFF14110A) else C.text, size = 26.sp)
                }
            }
        }
        Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            T("깨우기 기록", 36.sp, C.text, bold = true)
            T("이 차 화면 안에만 저장해요. 밖으로 보내지 않아요.", 22.sp, C.muted)
            if (ui.wakeRecords.isEmpty()) T("아직 없어요", 26.sp, C.muted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ui.wakeRecords.asReversed()) { r ->
                    Card(color = C.surface) {
                        T("${dateTime(r.atMs)} · ${r.restMinutes}분 쉼", 26.sp, bold = true)
                        T("${methodsText(r.methods)} · ${r.levelReached}단계에서 ${r.secondsToWake}초 만에 깸", 22.sp, C.muted)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 탭: 운행기록부

@Composable
private fun LogbookScreen(ui: UiState, c: AppController) {
    val s = LogbookCsv.summary(ui.logbook)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            T("업무용승용차 운행기록부", 48.sp, C.amber, bold = true)
            Spacer(Modifier.weight(1f))
            BigButton("CSV 내보내기", c::exportCsv, size = 26.sp)
            if (ui.logbook.any { it.demo }) BigButton("시연 줄 지우기", c::clearDemoRows, color = C.surface2, textColor = C.text, size = 24.sp)
        }
        T("총주행 ${km(s.totalKm)}km · 업무용 ${km(s.businessKm)}km · 업무사용비율 ${km(s.businessRatioPercent)}%   |   계기판 거리만 기록, 위치·경로는 저장하지 않음", 24.sp, C.muted)
        val weights = listOf(1.1f, 0.8f, 0.7f, 1f, 1f, 0.7f, 0.8f, 0.8f, 1.4f)
        Row(Modifier.fillMaxWidth().background(C.surface2).padding(12.dp)) {
            LogbookCsv.HEADER.forEachIndexed { i, h -> T(h, 18.sp, C.muted, bold = true, modifier = Modifier.weight(weights[i])) }
        }
        if (ui.logbook.isEmpty()) T("아직 기록이 없어요. 주행을 마치고 목적을 한 번 누르면 한 줄이 생겨요.", 26.sp, C.muted)
        LazyColumn {
            items(ui.logbook.asReversed()) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    LogbookCsv.row(e).forEachIndexed { i, cell -> T(cell, 22.sp, if (e.demo) C.muted else C.text, modifier = Modifier.weight(weights[i])) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 탭: 다음 사람 카드

@Composable
private fun HandoverScreen(ui: UiState, c: AppController) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            T("다음 사람 카드", 52.sp, C.blue, bold = true)
            val h = ui.handover
            if (h == null) {
                T("아직 없어요. 지난 운전자가 내리며 목적을 누르면 이 카드가 만들어져요.", 30.sp, C.muted)
            } else HandoverCardView(h)
        }
        Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Card {
                T("이번 운전자", 32.sp, C.text, bold = true)
                ui.drivers.forEachIndexed { i, d ->
                    BigButton(d.display, { c.selectDriver(i) }, Modifier.fillMaxWidth(),
                        color = if (i == ui.driverIndex) C.blue else C.surface2,
                        textColor = if (i == ui.driverIndex) Color(0xFF06121A) else C.text, size = 28.sp)
                }
            }
        }
    }
}

@Composable
private fun HandoverCardView(h: HandoverCard) {
    Card(color = C.surface) {
        T(HandoverBuilder.headline(h), 32.sp, C.text, bold = true)
        T("지난 운전자 ${h.previousDriver ?: "-"} · ${h.tripKm?.let { "${km(it)}km" } ?: "-"} · ${dateTime(h.createdAtMs)}" + if (h.demo) " · 시연 시나리오" else "", 24.sp, C.muted)
    }
    Card(color = C.surface) {
        T("지난 주행 중 켜졌다 꺼진 경고", 30.sp, C.amber, bold = true)
        T(h.onThenOff.joinToString { PublicWarnings.label(it) }.ifBlank { "없음" }, 30.sp)
        T("지금도 켜진 경고", 30.sp, C.red, bold = true)
        T(h.stillOn.joinToString { PublicWarnings.label(it) }.ifBlank { "없음" }, 30.sp)
        T("보는 경고: 엔진오일·브레이크액·워셔액 부족, 저전력", 22.sp, C.muted)
    }
    Card(color = C.surface) {
        T("연료 ${h.fuelPercent?.let { "$it%" } ?: "-"} · 배터리 ${h.evSocPercent?.let { "$it%" } ?: "-"}", 30.sp, C.text, bold = true)
        T("계기판 ${h.odometerKm?.let { "${km(it)} km" } ?: "-"}", 30.sp)
        T("타이어·경고등 상세는 권한 공개 시 추가", 22.sp, C.muted)
    }
}

// ---------------------------------------------------------------------------- 탭: SDK 기록

@Composable
private fun SdkLogScreen(ui: UiState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("SDK 호출 기록", 44.sp, C.text, bold = true)
        T("성공 ${ui.sdkLog.count { it.ok }} · 실패 ${ui.sdkLog.count { !it.ok }} (최근 200개). 실패해도 앱은 계속 동작해요.", 24.sp, C.muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.sdkLog) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    T(timeOnly(e.atMs), 20.sp, C.muted, modifier = Modifier.width(140.dp))
                    T(if (e.ok) "성공" else "실패", 20.sp, if (e.ok) C.mint else C.red, bold = true, modifier = Modifier.width(90.dp))
                    T(e.api, 20.sp, modifier = Modifier.weight(1f))
                    T(e.detail, 20.sp, C.muted, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 문자열 도우미

private fun methodsText(m: Set<WakeMethod>) =
    if (m.isEmpty()) "소리" else WakeMethod.entries.filter { it in m }.joinToString("·") { it.label }

private fun stageText(m: Set<WakeMethod>, level: Int): String {
    val parts = WakeSchedule.actionsFor(m, level).map {
        when (it) {
            is com.moyeon.waketidy.engine.WakeAction.Ambient -> "무드등 ${(it.fraction * 100).toInt()}%"
            is com.moyeon.waketidy.engine.WakeAction.ReadingLight -> "독서등 켜기"
            is com.moyeon.waketidy.engine.WakeAction.Fan -> "얼굴 쪽 바람 ${(it.fraction * 100).toInt()}%"
            is com.moyeon.waketidy.engine.WakeAction.SeatHeat -> "열선 ${(it.fraction * 100).toInt()}%"
            is com.moyeon.waketidy.engine.WakeAction.SeatVent -> "통풍 ${(it.fraction * 100).toInt()}%"
            is com.moyeon.waketidy.engine.WakeAction.Tone -> "알림음 ${it.beeps}번"
        }
    }
    return if (parts.isEmpty()) "" else parts.joinToString(" · ")
}

private fun minutes(ms: Long): String {
    val m = ms / 60_000L
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

private fun clock(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun km(v: Double) = "%,.1f".format(v)
private fun dateTime(ms: Long) = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(ms))
private fun timeOnly(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(ms))
