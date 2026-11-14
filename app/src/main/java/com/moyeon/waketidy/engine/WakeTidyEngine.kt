package com.moyeon.waketidy.engine

/** 엔진이 차량 어댑터(또는 화면)에 요청하는 일. */
sealed interface EngineOutput {
    /** 깨우기 단계 진입: 차에 동작을 보낸다. */
    data class Wake(val stage: WakeStage) : EngineOutput
    /** 깨우기 끝: 끄기 동작 + 기록 저장. */
    data class WakeDone(val record: WakeRecord, val endActions: List<WakeAction>) : EngineOutput
    data object RestCancelled : EngineOutput
    data object RecoveryDone : EngineOutput
    /** 주행 시작: 계기판 거리를 읽어 [WakeTidyEngine.startTrip] 으로 넘겨 달라. */
    data object NeedStartOdometer : EngineOutput
    /** 내릴 때 감지: 정리 카드를 띄운다. 진행 중인 주행이 있으면 끝 거리를 읽어 [WakeTidyEngine.endTrip] 으로. */
    data class ExitDetected(val items: List<TidyItem>, val needEndOdometer: Boolean) : EngineOutput
}

/**
 * 판정 엔진 묶음. 차 상태 한 장([VehicleSnapshot])과 시각을 받아 무엇을 할지 정한다.
 * 실제 차 신호와 시연 시나리오의 가상 신호가 같은 입구로 들어온다.
 */
class WakeTidyEngine(
    limitMinutes: Int = 100,
    methods: Set<WakeMethod> = setOf(WakeMethod.AMBIENT, WakeMethod.FAN, WakeMethod.SEAT_HEAT),
) {
    val timer = DrivingTimer(limitMs = limitMinutes * 60_000L)
    val rest = RestFlow(timer, methods)
    val exit = ExitDetector()
    val trips = TripRecorder()
    val warnings = WarningTracker()

    var snapshot = VehicleSnapshot()
        private set
    var exitItems: List<TidyItem> = emptyList()
        private set
    var exitVisible = false
        private set

    private var prevGear = GearState.UNKNOWN

    var limitMinutes: Int
        get() = (timer.limitMs / 60_000L).toInt()
        set(v) {
            timer.limitMs = v * 60_000L
        }

    var methods: Set<WakeMethod>
        get() = rest.methods
        set(v) {
            rest.methods = v
        }

    fun onSnapshot(s: VehicleSnapshot, nowMs: Long): List<EngineOutput> {
        snapshot = s
        val out = mutableListOf<EngineOutput>()

        if (trips.shouldStart(prevGear, s.gear)) out += EngineOutput.NeedStartOdometer
        if (trips.active != null) warnings.update(s.activeWarnings)

        rest.tick(s.gear, nowMs).forEach { out += toOutput(it) }

        if (s.gear.isMoving) exitVisible = false
        if (exit.update(s.gear, s.driverDoorOpen)) {
            exitItems = TidyPlanner.plan(s)
            exitVisible = true
            out += EngineOutput.ExitDetected(exitItems, needEndOdometer = trips.active != null)
        } else if (exitVisible) {
            // 카드가 떠 있는 동안 정리 결과(창 닫힘 등)를 반영한다. 이미 나온 항목은 상태 확인용으로 남긴다.
            exitItems = TidyPlanner.plan(s)
        }
        prevGear = s.gear
        return out
    }

    fun tick(nowMs: Long): List<EngineOutput> = onSnapshot(snapshot, nowMs)

    fun startTrip(km: Double, nowMs: Long, driver: Driver) {
        trips.start(km, nowMs, driver)
        warnings.reset()
        warnings.update(snapshot.activeWarnings)
    }

    fun endTrip(km: Double, nowMs: Long): PendingTrip? = trips.end(km, nowMs)

    fun closeExitCard() {
        exitVisible = false
    }

    fun chooseRest(minutes: Int, nowMs: Long) = rest.chooseRest(minutes, nowMs)
    fun dismissRest() = rest.dismiss()
    fun wakeNow(nowMs: Long) = rest.wakeNow(nowMs).map(::toOutput)
    fun cancelRest() = rest.cancelRest().map(::toOutput)
    fun awake(nowMs: Long) = rest.awake(nowMs).map(::toOutput)
    fun skipRecovery() = rest.skipRecovery().map(::toOutput)

    /** 목적 한 탭 → 운행기록부 한 줄 + 다음 사람 카드. */
    fun completeTrip(
        purpose: Purpose,
        memo: String,
        date: java.time.LocalDate,
        nowMs: Long,
        demo: Boolean = false,
    ): Pair<LogbookEntry, HandoverCard>? {
        val entry = trips.complete(purpose, memo, date, demo) ?: return null
        val card = HandoverBuilder.build(nowMs, warnings, snapshot, entry.driver.display, entry.distanceKm, demo)
        warnings.reset()
        return entry to card
    }

    fun reset() {
        timer.reset()
        rest.reset()
        exit.reset()
        trips.reset()
        warnings.reset()
        exitVisible = false
        exitItems = emptyList()
        prevGear = GearState.UNKNOWN
        snapshot = VehicleSnapshot()
    }

    private fun toOutput(e: RestEvent): EngineOutput = when (e) {
        is RestEvent.StageEntered -> EngineOutput.Wake(e.stage)
        is RestEvent.WakeEnded -> EngineOutput.WakeDone(e.record, e.endActions)
        RestEvent.Cancelled -> EngineOutput.RestCancelled
        RestEvent.RecoveryDone -> EngineOutput.RecoveryDone
    }
}
