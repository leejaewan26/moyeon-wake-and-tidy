package com.moyeon.waketidy.engine

/**
 * 판정 엔진이 쓰는 차량 신호 모델. Android·Pleos SDK 에 의존하지 않는다.
 * 차량 어댑터(실제 SDK)와 시연 시나리오(가상 신호)가 같은 모델로 엔진에 값을 넣는다.
 */
enum class GearState {
    PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN;

    /** 차가 움직일 수 있는 기어(연속 운전 시간을 누적하는 기어). */
    val isMoving: Boolean get() = this == DRIVE || this == REVERSE || this == NEUTRAL
}

/** 좌석·창·문 위치 코드. SDK 의 DoorValue/WindowValue/SeatValue 이름과 같게 둔다. */
object Areas {
    const val DRIVER = "ROW_1_LEFT"
    const val PASSENGER = "ROW_1_RIGHT"
    const val REAR_LEFT = "ROW_2_LEFT"
    const val REAR_RIGHT = "ROW_2_RIGHT"
    val CABIN = listOf(DRIVER, PASSENGER, REAR_LEFT, REAR_RIGHT)
    val REAR = listOf(REAR_LEFT, REAR_RIGHT)

    fun label(code: String): String = when (code) {
        DRIVER -> "운전석"
        PASSENGER -> "조수석"
        REAR_LEFT -> "뒷좌석 왼쪽"
        REAR_RIGHT -> "뒷좌석 오른쪽"
        else -> code
    }
}

/**
 * 한 시점의 차 상태. null 은 "아직 못 읽음/이 차에서 지원 안 함".
 * 위치(GPS)·경로는 담지 않는다(SDK 에도 없고, 저장하지도 않는다).
 */
data class VehicleSnapshot(
    val gear: GearState = GearState.UNKNOWN,
    /** 운전석 문 위치. 0 = 닫힘, 0보다 크면 열림. */
    val driverDoorPosition: Int? = null,
    /** 창 위치(영역 코드 → 값). 0 = 닫힘. */
    val windows: Map<String, Int?> = emptyMap(),
    /** 독서등 켜짐 여부(좌석 코드 → 켜짐). */
    val readingLights: Map<String, Boolean?> = emptyMap(),
    /** 뒷좌석 착석(좌석 코드 → 착석). */
    val rearOccupied: Map<String, Boolean?> = emptyMap(),
    val driverDoorLocked: Boolean? = null,
    /** 지금 켜져 있는 공개 경고 신호([PublicWarnings] 이름). */
    val activeWarnings: Set<String> = emptySet(),
    val fuelPercent: Int? = null,
    val evSocPercent: Int? = null,
    /** 계기판 누적 거리(km). */
    val odometerKm: Double? = null,
) {
    val driverDoorOpen: Boolean get() = (driverDoorPosition ?: 0) > 0
}
