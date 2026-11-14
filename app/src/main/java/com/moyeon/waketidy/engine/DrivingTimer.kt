package com.moyeon.waketidy.engine

/**
 * 연속 운전 시간 누적기.
 * - 기어가 D·R·N 인 동안 시간을 더한다(P·UNKNOWN 은 더하지 않음).
 * - P단에 [resetAfterParkMs] 이상 서 있었거나 쉼(깨우기)을 마치면 0 으로 돌린다.
 * - 시간은 밖에서 넣어 준다(실제 시계 또는 시연용 가상 시계).
 */
class DrivingTimer(
    var limitMs: Long = 100 * 60_000L,
    private val resetAfterParkMs: Long = 15 * 60_000L,
) {
    var drivingMs: Long = 0L
        private set
    var gear: GearState = GearState.UNKNOWN
        private set

    private var lastMs: Long? = null
    private var parkedSinceMs: Long? = null

    fun update(newGear: GearState, nowMs: Long) {
        accumulate(nowMs)
        if (newGear != gear) {
            if (newGear == GearState.PARK) {
                parkedSinceMs = nowMs
            } else if (gear == GearState.PARK) {
                parkedSinceMs = null
            }
            gear = newGear
        }
        checkLongPark(nowMs)
    }

    fun tick(nowMs: Long) {
        accumulate(nowMs)
        checkLongPark(nowMs)
    }

    /** 쉼을 마쳤을 때 호출. */
    fun restTaken() {
        drivingMs = 0L
    }

    fun reset() {
        drivingMs = 0L
        gear = GearState.UNKNOWN
        lastMs = null
        parkedSinceMs = null
    }

    val isDue: Boolean get() = drivingMs >= limitMs

    /** 기준 시간을 넘겼고 지금 P단이면 쉼을 제안한다. */
    val shouldSuggestRest: Boolean get() = isDue && gear == GearState.PARK

    private fun accumulate(nowMs: Long) {
        val last = lastMs
        if (last != null && gear.isMoving) drivingMs += (nowMs - last).coerceAtLeast(0L)
        lastMs = nowMs
    }

    private fun checkLongPark(nowMs: Long) {
        val since = parkedSinceMs ?: return
        if (gear == GearState.PARK && nowMs - since >= resetAfterParkMs) drivingMs = 0L
    }
}
