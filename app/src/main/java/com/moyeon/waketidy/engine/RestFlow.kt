package com.moyeon.waketidy.engine

/** 장면 1 '쉴 때' 의 상태. */
sealed interface RestState {
    data object Idle : RestState
    data class Suggested(val drivingMinutes: Long) : RestState
    data class Resting(val startMs: Long, val endMs: Long, val minutes: Int) : RestState
    data class Waking(val startMs: Long, val level: Int, val restMinutes: Int) : RestState
    data class Recovering(val endMs: Long) : RestState
}

sealed interface RestEvent {
    data class StageEntered(val stage: WakeStage) : RestEvent
    data class WakeEnded(val record: WakeRecord, val endActions: List<WakeAction>) : RestEvent
    data object RecoveryDone : RestEvent
    data object Cancelled : RestEvent
}

/** 깨우기 기록(차 밖으로 보내지 않고 앱 안에만 저장). */
data class WakeRecord(
    val atMs: Long,
    val restMinutes: Int,
    val methods: Set<WakeMethod>,
    val levelReached: Int,
    val secondsToWake: Long,
)

/**
 * 쉼 제안 → 쉬는 중 → 깨우기(단계적으로 강해짐) → 2분 회복 → 끝.
 * 주행 중(기어≠P)에는 제안하지 않고, 쉬는 중에 출발하면 취소한다.
 */
class RestFlow(
    val timer: DrivingTimer,
    var methods: Set<WakeMethod> = setOf(WakeMethod.AMBIENT, WakeMethod.FAN, WakeMethod.SEAT_HEAT),
    private val recoveryMs: Long = 2 * 60_000L,
) {
    var state: RestState = RestState.Idle
        private set

    /** "나중에" 를 누르면 다음에 P단에 새로 설 때까지 다시 묻지 않는다. */
    private var snoozedUntilMove = false

    fun tick(gear: GearState, nowMs: Long): List<RestEvent> {
        timer.update(gear, nowMs)
        if (gear.isMoving) snoozedUntilMove = false
        val events = mutableListOf<RestEvent>()
        when (val s = state) {
            RestState.Idle -> {
                if (timer.shouldSuggestRest && !snoozedUntilMove) {
                    state = RestState.Suggested(timer.drivingMs / 60_000L)
                }
            }
            is RestState.Suggested -> if (gear.isMoving) state = RestState.Idle
            is RestState.Resting -> when {
                gear.isMoving -> {
                    state = RestState.Idle
                    events += RestEvent.Cancelled
                }
                nowMs >= s.endMs -> events += startWaking(nowMs, s.minutes)
            }
            is RestState.Waking -> {
                val level = WakeSchedule.levelAt((nowMs - s.startMs) / 1000)
                if (level > s.level) {
                    val stages = WakeSchedule.stages(methods)
                    for (l in (s.level + 1)..level) events += RestEvent.StageEntered(stages[l - 1])
                    state = s.copy(level = level)
                }
            }
            is RestState.Recovering -> if (nowMs >= s.endMs || gear.isMoving) {
                state = RestState.Idle
                events += RestEvent.RecoveryDone
            }
        }
        return events
    }

    fun chooseRest(minutes: Int, nowMs: Long) {
        require(minutes > 0)
        state = RestState.Resting(nowMs, nowMs + minutes * 60_000L, minutes)
    }

    fun dismiss() {
        if (state is RestState.Suggested) {
            state = RestState.Idle
            snoozedUntilMove = true
        }
    }

    /** 쉬는 중에 바로 깨우기 시작(시연·미리 보기). */
    fun wakeNow(nowMs: Long): List<RestEvent> {
        val s = state as? RestState.Resting ?: return emptyList()
        return startWaking(nowMs, s.minutes)
    }

    fun cancelRest(): List<RestEvent> {
        if (state is RestState.Resting) {
            state = RestState.Idle
            snoozedUntilMove = true
            return listOf(RestEvent.Cancelled)
        }
        return emptyList()
    }

    /** 운전자가 "깼어요" 를 눌렀다. */
    fun awake(nowMs: Long): List<RestEvent> {
        val s = state as? RestState.Waking ?: return emptyList()
        val record = WakeRecord(
            atMs = nowMs,
            restMinutes = s.restMinutes,
            methods = methods,
            levelReached = s.level,
            secondsToWake = ((nowMs - s.startMs) / 1000).coerceAtLeast(0L),
        )
        timer.restTaken()
        state = RestState.Recovering(nowMs + recoveryMs)
        return listOf(RestEvent.WakeEnded(record, WakeSchedule.endActions(methods)))
    }

    /** 회복 화면을 건너뛰고 출발. */
    fun skipRecovery(): List<RestEvent> {
        if (state !is RestState.Recovering) return emptyList()
        state = RestState.Idle
        return listOf(RestEvent.RecoveryDone)
    }

    fun reset() {
        state = RestState.Idle
        snoozedUntilMove = false
    }

    private fun startWaking(nowMs: Long, minutes: Int): List<RestEvent> {
        state = RestState.Waking(nowMs, level = 1, restMinutes = minutes)
        return listOf(RestEvent.StageEntered(WakeSchedule.stages(methods).first()))
    }
}
