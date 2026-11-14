package com.moyeon.waketidy.engine

/** 운전자가 고르는 깨우는 방식(프로필에 저장). */
enum class WakeMethod(val label: String, val group: String) {
    AMBIENT("무드등", "빛"),
    READING("독서등", "빛"),
    FAN("바람(팬·풍향)", "바람"),
    SEAT_HEAT("열선", "열선/통풍"),
    SEAT_VENT("통풍", "열선/통풍"),
}

/** 깨우기 단계에서 차에 보낼 동작. 세기는 0..1 비율(어댑터가 차의 최소·최대 값으로 바꾼다). */
sealed interface WakeAction {
    data class Ambient(val fraction: Float) : WakeAction
    data class ReadingLight(val on: Boolean) : WakeAction
    data class Fan(val fraction: Float, val toFace: Boolean) : WakeAction
    data class SeatHeat(val fraction: Float) : WakeAction
    data class SeatVent(val fraction: Float) : WakeAction
    data class Tone(val beeps: Int) : WakeAction
}

data class WakeStage(val level: Int, val startSec: Int, val actions: List<WakeAction>)

/**
 * 깨우기 단계 스케줄: 30초마다 한 단계씩, 3단계까지 점점 강해진다.
 * 1단계는 조용히(빛·약한 바람), 2단계부터 소리를 더한다.
 */
object WakeSchedule {
    const val STAGE_INTERVAL_SEC = 30
    const val MAX_LEVEL = 3

    fun stages(methods: Set<WakeMethod>): List<WakeStage> =
        (1..MAX_LEVEL).map { level ->
            WakeStage(level, (level - 1) * STAGE_INTERVAL_SEC, actionsFor(methods, level))
        }

    /** 깨우기 시작 후 [elapsedSec] 초일 때 단계(1..3). */
    fun levelAt(elapsedSec: Long): Int =
        (elapsedSec.coerceAtLeast(0L) / STAGE_INTERVAL_SEC + 1).toInt().coerceAtMost(MAX_LEVEL)

    fun actionsFor(methods: Set<WakeMethod>, level: Int): List<WakeAction> {
        require(level in 1..MAX_LEVEL) { "level must be 1..$MAX_LEVEL" }
        val out = mutableListOf<WakeAction>()
        if (WakeMethod.AMBIENT in methods) out += WakeAction.Ambient(level / 3f)
        if (WakeMethod.READING in methods) {
            // 무드등과 같이 고르면 무드등으로 먼저 밝히고 2단계에 독서등을 더한다.
            val readingFrom = if (WakeMethod.AMBIENT in methods) 2 else 1
            if (level == readingFrom) out += WakeAction.ReadingLight(true)
        }
        if (WakeMethod.FAN in methods) {
            out += WakeAction.Fan(fraction = FAN_STEPS[level - 1], toFace = true)
        }
        if (WakeMethod.SEAT_HEAT in methods) out += WakeAction.SeatHeat(level / 3f)
        if (WakeMethod.SEAT_VENT in methods) out += WakeAction.SeatVent(level / 3f)
        if (level >= 2) out += WakeAction.Tone(beeps = if (level == 2) 1 else 3)
        return out
    }

    /** 깨운 뒤 끄는 동작(무드등·팬은 어댑터가 깨우기 전 값으로 되돌린다). */
    fun endActions(methods: Set<WakeMethod>): List<WakeAction> = buildList {
        if (WakeMethod.READING in methods) add(WakeAction.ReadingLight(false))
        if (WakeMethod.SEAT_HEAT in methods) add(WakeAction.SeatHeat(0f))
        if (WakeMethod.SEAT_VENT in methods) add(WakeAction.SeatVent(0f))
    }

    private val FAN_STEPS = floatArrayOf(0.25f, 0.5f, 1.0f)
}
