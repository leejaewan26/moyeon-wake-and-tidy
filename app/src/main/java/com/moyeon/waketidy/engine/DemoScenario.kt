package com.moyeon.waketidy.engine

/** 시나리오가 화면 대신 누르는 버튼. */
enum class DemoTap { CHOOSE_REST_10, AWAKE, TIDY_ALL, PURPOSE_CLIENT_VISIT }

/**
 * 시연 시나리오 한 걸음.
 * @param atMs 시나리오 시작 후 실제 경과 시간
 * @param advanceClockMs 가상 시계를 이만큼 앞으로 돌린다(100분 운전, 10분 쉼 등을 몇 초로 줄임)
 * @param change 가상 차 신호 변경
 */
data class DemoStep(
    val atMs: Long,
    val caption: String,
    val advanceClockMs: Long = 0L,
    val change: ((VehicleSnapshot) -> VehicleSnapshot)? = null,
    val tap: DemoTap? = null,
)

/**
 * 30초 시연: "100분째예요" → P단 쉼 10분 → 빛·바람·열선으로 깨움 → 2분 회복
 * → 문 열림 → 창문·독서등 원탭 정리·잠금 → 목적 한 탭 → 운행기록부·다음 사람 카드.
 * 에뮬레이터 VHAL 패널을 만지지 않고도 같은 엔진으로 재현한다(신호만 가상, 차량 쓰기는 실제 SDK 호출).
 */
object DemoScenario {
    const val START_KM = 12_340.0
    const val TRIP_KM = 31.4

    val initial = VehicleSnapshot(
        gear = GearState.PARK,
        driverDoorPosition = 0,
        windows = Areas.CABIN.associateWith { 0 },
        readingLights = Areas.CABIN.associateWith { false },
        rearOccupied = Areas.REAR.associateWith { false },
        driverDoorLocked = false,
        activeWarnings = emptySet(),
        fuelPercent = 42,
        evSocPercent = null,
        odometerKm = START_KM,
    )

    fun steps(limitMinutes: Int): List<DemoStep> = listOf(
        DemoStep(0, "사원 탑승 · 기어 P", change = { initial }),
        DemoStep(1_500, "출발(P→D) — 계기판 ${"%,.1f".format(START_KM)}km 기록", change = {
            it.copy(
                gear = GearState.DRIVE,
                windows = it.windows + (Areas.PASSENGER to 40),
                readingLights = it.readingLights + (Areas.REAR_LEFT to true),
                rearOccupied = it.rearOccupied + (Areas.REAR_RIGHT to true),
            )
        }),
        DemoStep(3_000, "연속 운전 ${limitMinutes}분 경과(가상 시간) · 워셔액 부족 경고 켜짐", advanceClockMs = limitMinutes * 60_000L, change = {
            it.copy(
                odometerKm = START_KM + TRIP_KM,
                activeWarnings = setOf(PublicWarnings.WASHER_FLUID_LOW),
                fuelPercent = 18,
            )
        }),
        DemoStep(4_500, "워셔액 경고 꺼짐(주행 중 이력은 남음)", change = { it.copy(activeWarnings = emptySet()) }),
        DemoStep(6_000, "휴게소 도착 · 기어 P → 쉼 제안", change = { it.copy(gear = GearState.PARK) }),
        DemoStep(8_000, "10분 쉬기 선택", tap = DemoTap.CHOOSE_REST_10),
        DemoStep(10_000, "10분 경과(가상) — 1단계 깨우기", advanceClockMs = 10 * 60_000L),
        DemoStep(13_000, "30초 더 — 2단계(더 밝게·더 세게·소리)", advanceClockMs = WakeSchedule.STAGE_INTERVAL_SEC * 1000L),
        DemoStep(16_000, "30초 더 — 3단계", advanceClockMs = WakeSchedule.STAGE_INTERVAL_SEC * 1000L),
        DemoStep(18_500, "\"깼어요\" — 2분 회복", tap = DemoTap.AWAKE),
        DemoStep(20_500, "2분 경과(가상) — 회복 끝", advanceClockMs = 2 * 60_000L),
        DemoStep(21_500, "목적지 도착 · 운전석 문 열림 → 내릴 때 카드", change = { it.copy(driverDoorPosition = 1) }),
        DemoStep(24_500, "원탭 정리: 창 닫기·독서등 끄기·문 잠금", tap = DemoTap.TIDY_ALL),
        DemoStep(27_000, "목적 한 탭: 거래처 방문 → 운행기록부 한 줄", tap = DemoTap.PURPOSE_CLIENT_VISIT),
        DemoStep(30_000, "시연 끝 — 다음 사람 카드가 만들어졌어요"),
    )
}
