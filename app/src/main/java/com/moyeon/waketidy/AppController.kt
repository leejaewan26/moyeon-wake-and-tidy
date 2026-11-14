package com.moyeon.waketidy

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moyeon.waketidy.data.Store
import com.moyeon.waketidy.engine.DemoScenario
import com.moyeon.waketidy.engine.DemoTap
import com.moyeon.waketidy.engine.Driver
import com.moyeon.waketidy.engine.EngineOutput
import com.moyeon.waketidy.engine.GearState
import com.moyeon.waketidy.engine.HandoverCard
import com.moyeon.waketidy.engine.LogbookEntry
import com.moyeon.waketidy.engine.PendingTrip
import com.moyeon.waketidy.engine.Purpose
import com.moyeon.waketidy.engine.RecentPurposes
import com.moyeon.waketidy.engine.RestState
import com.moyeon.waketidy.engine.TidyItem
import com.moyeon.waketidy.engine.TidyKind
import com.moyeon.waketidy.engine.VehicleSnapshot
import com.moyeon.waketidy.engine.WakeMethod
import com.moyeon.waketidy.engine.WakeRecord
import com.moyeon.waketidy.engine.WakeTidyEngine
import com.moyeon.waketidy.vehicle.SdkLogEntry
import com.moyeon.waketidy.vehicle.VehicleGateway
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Tab(val label: String) { HOME("지금"), PROFILE("깨우는 방식"), LOGBOOK("운행기록부"), HANDOVER("다음 사람 카드"), SDK("SDK 기록") }

enum class TidyStatus { WAITING, RUNNING, DONE, FAILED }

data class TidyRow(val item: TidyItem, val status: TidyStatus = TidyStatus.WAITING, val detail: String = "")

data class UiState(
    val nowMs: Long = 0L,
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val drivingMs: Long = 0L,
    val limitMinutes: Int = 100,
    val rest: RestState = RestState.Idle,
    val scenarioRunning: Boolean = false,
    val scenarioCaption: String = "",
    val sdkReady: Boolean = false,
    /** AAOS GEAR_SELECTION 을 읽었는지(null = 확인 전). */
    val gearSupported: Boolean? = null,
    val exitVisible: Boolean = false,
    val tidyRows: List<TidyRow> = emptyList(),
    val pendingTrip: PendingTrip? = null,
    /** 이번 하차에서 방금 적은 운행기록부 줄(확인 표시용). */
    val lastEntry: LogbookEntry? = null,
    val tripActive: Boolean = false,
    val tripStartKm: Double? = null,
    val recentPurposes: List<Pair<Purpose, String>> = emptyList(),
    val logbook: List<LogbookEntry> = emptyList(),
    val handover: HandoverCard? = null,
    val methods: Set<WakeMethod> = emptySet(),
    val wakeRecords: List<WakeRecord> = emptyList(),
    val drivers: List<Driver> = emptyList(),
    val driverIndex: Int = 0,
    val tab: Tab = Tab.HOME,
    val sdkLog: List<SdkLogEntry> = emptyList(),
    val message: String = "",
) {
    val gear: GearState get() = snapshot.gear
    val driving: Boolean get() = snapshot.gear.isMoving
    val driver: Driver? get() = drivers.getOrNull(driverIndex)
}

/**
 * 화면 ↔ 엔진 ↔ 차량 어댑터를 잇는다.
 * 실제 차 신호와 시나리오의 가상 신호는 같은 [WakeTidyEngine.onSnapshot] 으로 들어간다.
 * 시나리오 재생 중에도 차량 쓰기(창·독서등·잠금·팬·무드등·열선)는 실제 SDK 를 호출한다.
 */
class AppController(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val engine = WakeTidyEngine(store.limitMinutes, store.methods)
    private val gateway = VehicleGateway(app, onLog = ::onSdkLog, onSnapshot = ::onRealSnapshot,
        onGearSupported = { ok -> _ui.value = _ui.value.copy(gearSupported = ok) })

    private val _ui = MutableStateFlow(
        UiState(
            limitMinutes = store.limitMinutes,
            methods = store.methods,
            wakeRecords = store.wakeRecords(),
            logbook = store.logbook(),
            handover = store.handover(),
            drivers = store.drivers,
            driverIndex = store.driverIndex,
        ).let { it.copy(recentPurposes = RecentPurposes.from(it.logbook)) }
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var scenarioJob: Job? = null
    private var virtual = VehicleSnapshot()
    private var virtualOffsetMs = 0L
    private val scenario get() = scenarioJob?.isActive == true
    private var tidyRows: List<TidyRow> = emptyList()
    private var pendingTrip: PendingTrip? = null

    init {
        val ready = gateway.start()
        _ui.value = _ui.value.copy(sdkReady = ready)
        viewModelScope.launch {
            while (isActive) {
                handle(engine.tick(now()))
                publish()
                delay(500)
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis() + if (scenario) virtualOffsetMs else 0L

    // ------------------------------------------------------------------ 신호 입력

    private fun onRealSnapshot(s: VehicleSnapshot) {
        if (scenario) return // 시나리오 중에는 실제 신호를 엔진에 넣지 않는다(화면 표시도 가상 값).
        handle(engine.onSnapshot(s, now()))
        publish()
    }

    private fun feedVirtual(s: VehicleSnapshot) {
        virtual = s
        handle(engine.onSnapshot(s, now()))
        publish()
    }

    private fun handle(outputs: List<EngineOutput>) {
        for (o in outputs) when (o) {
            is EngineOutput.Wake -> gateway.applyWake(o.stage.actions)
            is EngineOutput.WakeDone -> {
                gateway.endWake(o.endActions)
                store.addWakeRecord(o.record)
                _ui.value = _ui.value.copy(wakeRecords = store.wakeRecords())
            }
            EngineOutput.NeedStartOdometer -> readOdometer { km ->
                val d = _ui.value.driver ?: return@readOdometer
                if (km != null) engine.startTrip(km, now(), d)
                else toast("주행 시작 계기판 거리를 읽지 못했어요(운행기록부 줄을 만들 수 없음)")
            }
            is EngineOutput.ExitDetected -> {
                tidyRows = o.items.map { TidyRow(it) }
                set { it.copy(lastEntry = null) }
                if (o.needEndOdometer) readOdometer { km ->
                    if (km != null) pendingTrip = engine.endTrip(km, now())
                    else toast("주행 끝 계기판 거리를 읽지 못했어요")
                    publish()
                }
            }
            EngineOutput.RestCancelled, EngineOutput.RecoveryDone -> Unit
        }
    }

    private fun readOdometer(cb: (Double?) -> Unit) {
        if (scenario) cb(virtual.odometerKm) else gateway.readOdometer(cb)
    }

    // ------------------------------------------------------------------ 화면 동작

    fun selectTab(t: Tab) = set { it.copy(tab = t) }

    fun chooseRest(minutes: Int) {
        engine.chooseRest(minutes, now()); publish()
    }

    fun dismissRest() {
        engine.dismissRest(); publish()
    }

    fun wakeNow() {
        handle(engine.wakeNow(now())); publish()
    }

    fun cancelRest() {
        handle(engine.cancelRest()); publish()
    }

    fun awake() {
        handle(engine.awake(now())); publish()
    }

    fun skipRecovery() {
        handle(engine.skipRecovery()); publish()
    }

    fun tidyAll() {
        tidyRows.forEachIndexed { i, row ->
            if (!row.item.actionable || row.status == TidyStatus.DONE || row.status == TidyStatus.RUNNING) return@forEachIndexed
            updateRow(i) { it.copy(status = TidyStatus.RUNNING, detail = "") }
            gateway.tidy(row.item) { ok, detail ->
                updateRow(i) { it.copy(status = if (ok) TidyStatus.DONE else TidyStatus.FAILED, detail = detail) }
                if (ok && scenario) feedVirtual(applyTidy(virtual, row.item))
                publish()
            }
        }
        publish()
    }

    private fun applyTidy(s: VehicleSnapshot, item: TidyItem): VehicleSnapshot = when (item.kind) {
        TidyKind.CLOSE_WINDOW -> s.copy(windows = s.windows + (item.area!! to 0))
        TidyKind.READING_LIGHT_OFF -> s.copy(readingLights = s.readingLights + (item.area!! to false))
        TidyKind.LOCK_DOORS -> s.copy(driverDoorLocked = true)
        TidyKind.REAR_SEAT_CHECK -> s
    }

    fun closeExitCard() {
        engine.closeExitCard(); publish()
    }

    fun choosePurpose(purpose: Purpose, memo: String) {
        val r = engine.completeTrip(purpose, memo, LocalDate.now(), System.currentTimeMillis(), demo = scenario)
        pendingTrip = null
        if (r == null) {
            toast("기록할 주행이 없어요")
            return
        }
        store.addLogbook(r.first)
        store.saveHandover(r.second)
        val book = store.logbook()
        set { it.copy(logbook = book, recentPurposes = RecentPurposes.from(book), handover = r.second, lastEntry = r.first, message = "운행기록부에 한 줄 적었어요: ${r.first.distanceKm}km · ${r.first.note}") }
        publish()
    }

    fun selectDriver(i: Int) {
        if (_ui.value.driving) return
        store.driverIndex = i
        set { it.copy(driverIndex = i, tab = Tab.HANDOVER) }
    }

    fun toggleMethod(m: WakeMethod) {
        val next = _ui.value.methods.let { if (m in it) it - m else it + m }
        store.methods = next
        engine.methods = next
        set { it.copy(methods = next) }
    }

    fun setLimit(minutes: Int) {
        store.limitMinutes = minutes
        engine.limitMinutes = minutes
        set { it.copy(limitMinutes = minutes) }
    }

    fun exportCsv() {
        runCatching { store.exportCsv(_ui.value.logbook, LocalDate.now()) }
            .onSuccess { toast("CSV 저장: ${it.absolutePath}") }
            .onFailure { toast("CSV 저장 실패: ${it.message}") }
    }

    fun clearDemoRows() {
        store.clearDemoLogbook()
        val book = store.logbook()
        set { it.copy(logbook = book, recentPurposes = RecentPurposes.from(book), message = "시연 줄을 지웠어요") }
    }

    // ------------------------------------------------------------------ 시연 시나리오

    fun toggleScenario() = if (scenario) stopScenario("시나리오를 멈췄어요") else playScenario()

    fun startScenarioIfIdle() {
        if (!scenario && !_ui.value.driving) playScenario()
    }

    private fun playScenario() {
        engine.reset()
        tidyRows = emptyList()
        pendingTrip = null
        virtualOffsetMs = 0L
        virtual = VehicleSnapshot()
        set { it.copy(tab = Tab.HOME, message = "") }
        scenarioJob = viewModelScope.launch {
            var last = 0L
            for (step in DemoScenario.steps(_ui.value.limitMinutes)) {
                delay(step.atMs - last)
                last = step.atMs
                Log.i(VehicleGateway.TAG, "scenario: ${step.caption}")
                virtualOffsetMs += step.advanceClockMs
                set { it.copy(scenarioCaption = step.caption) }
                feedVirtual(step.change?.invoke(virtual) ?: virtual)
                when (step.tap) {
                    DemoTap.CHOOSE_REST_10 -> chooseRest(10)
                    DemoTap.AWAKE -> awake()
                    DemoTap.TIDY_ALL -> tidyAll()
                    DemoTap.PURPOSE_CLIENT_VISIT -> choosePurpose(Purpose.CLIENT_VISIT, "")
                    null -> Unit
                }
            }
            delay(2_500)
            stopScenario("시연이 끝났어요. 운행기록부에 시연 줄 하나, 다음 사람 카드가 만들어졌어요.", Tab.HANDOVER)
        }
        publish()
    }

    private fun stopScenario(msg: String, tab: Tab = Tab.HOME) {
        val job = scenarioJob
        scenarioJob = null
        job?.cancel()
        if (engine.rest.state is RestState.Waking) gateway.endWake(emptyList())
        engine.reset()
        tidyRows = emptyList()
        pendingTrip = null
        virtualOffsetMs = 0L
        set { it.copy(scenarioCaption = "", message = msg, tab = tab) }
        onRealSnapshot(gateway.real)
    }

    // ------------------------------------------------------------------ 상태 내보내기

    private fun onSdkLog(e: SdkLogEntry) = set { it.copy(sdkLog = (listOf(e) + it.sdkLog).take(200)) }

    private fun updateRow(i: Int, f: (TidyRow) -> TidyRow) {
        tidyRows = tidyRows.mapIndexed { j, r -> if (j == i) f(r) else r }
    }

    private fun toast(msg: String) = set { it.copy(message = msg) }

    private fun set(f: (UiState) -> UiState) {
        _ui.value = f(_ui.value)
    }

    private fun publish() {
        // 차 신호로 이미 정리된 항목(카드에 없어진 것)은 '확인됨' 으로 표시
        val stillOpen = engine.exitItems.toSet()
        tidyRows = tidyRows.map { r ->
            if (r.item.actionable && r.item !in stillOpen && r.status != TidyStatus.DONE) r.copy(status = TidyStatus.DONE, detail = "차 신호로 확인") else r
        }
        val snap = if (scenario) virtual else engine.snapshot
        _ui.value = _ui.value.copy(
            nowMs = now(),
            snapshot = snap,
            drivingMs = engine.timer.drivingMs,
            rest = engine.rest.state,
            scenarioRunning = scenario,
            exitVisible = engine.exitVisible,
            tidyRows = tidyRows,
            pendingTrip = pendingTrip,
            tripActive = engine.trips.active != null,
            tripStartKm = engine.trips.active?.startKm,
        )
    }

    override fun onCleared() {
        scenarioJob?.cancel()
        gateway.stop()
    }
}
