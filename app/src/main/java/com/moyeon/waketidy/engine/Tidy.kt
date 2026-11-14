package com.moyeon.waketidy.engine

/**
 * 장면 2 '내릴 때': P단 + 운전석 문 열림을 한 번만 알린다.
 * 기어가 P 를 벗어나면(다음 주행) 다시 알릴 수 있게 된다.
 */
class ExitDetector {
    private var armed = true

    fun update(gear: GearState, driverDoorOpen: Boolean): Boolean {
        if (gear.isMoving) {
            armed = true
            return false
        }
        if (armed && gear == GearState.PARK && driverDoorOpen) {
            armed = false
            return true
        }
        return false
    }

    fun reset() {
        armed = true
    }
}

enum class TidyKind { REAR_SEAT_CHECK, CLOSE_WINDOW, READING_LIGHT_OFF, LOCK_DOORS }

data class TidyItem(
    val kind: TidyKind,
    val area: String?,
    val label: String,
    /** 원탭 정리로 차에 쓰기를 보내는 항목인지(뒷좌석 확인은 알림만). */
    val actionable: Boolean,
)

/** 차 상태 한 장 → 정리할 것 목록. */
object TidyPlanner {
    fun plan(s: VehicleSnapshot): List<TidyItem> {
        val items = mutableListOf<TidyItem>()
        for (seat in Areas.REAR) {
            if (s.rearOccupied[seat] == true) {
                items += TidyItem(TidyKind.REAR_SEAT_CHECK, seat, "${Areas.label(seat)}에 아직 누가(무엇이) 있어요", false)
            }
        }
        for (area in Areas.CABIN) {
            val pos = s.windows[area] ?: continue
            if (pos > 0) items += TidyItem(TidyKind.CLOSE_WINDOW, area, "${Areas.label(area)} 창문이 열려 있어요", true)
        }
        for (seat in Areas.CABIN) {
            if (s.readingLights[seat] == true) {
                items += TidyItem(TidyKind.READING_LIGHT_OFF, seat, "${Areas.label(seat)} 독서등이 켜져 있어요", true)
            }
        }
        if (s.driverDoorLocked != true) {
            items += TidyItem(TidyKind.LOCK_DOORS, null, "문 잠그기", true)
        }
        return items
    }
}
