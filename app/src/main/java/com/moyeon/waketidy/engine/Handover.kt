package com.moyeon.waketidy.engine

/**
 * 인수인계에서 보는 경고. 공개 권한(CAR_SAFETY)으로 읽히는 Safety 신호만 쓴다.
 * 경고등 목록(Telltales)·타이어 공기압은 비공개 권한이 필요해 쓰지 않는다.
 */
object PublicWarnings {
    const val ENGINE_OIL_LOW = "ENGINE_OIL_LOW"
    const val BRAKE_FLUID_LOW = "BRAKE_FLUID_LOW"
    const val WASHER_FLUID_LOW = "WASHER_FLUID_LOW"
    const val POWER_DOWN_WARNING = "POWER_DOWN_WARNING"

    val LABELS: Map<String, String> = linkedMapOf(
        ENGINE_OIL_LOW to "엔진오일 부족",
        BRAKE_FLUID_LOW to "브레이크액 부족",
        WASHER_FLUID_LOW to "워셔액 부족",
        POWER_DOWN_WARNING to "저전력 경고",
    )

    fun label(name: String): String = LABELS[name] ?: name
}

/** 주행 중 켜졌다 꺼진 경고를 모은다. */
class WarningTracker(private val watched: Set<String> = PublicWarnings.LABELS.keys) {
    private val seen = linkedSetOf<String>()
    private var current: Set<String> = emptySet()

    fun update(active: Set<String>) {
        current = active.filter { it in watched }.toSet()
        seen += current
    }

    fun onThenOff(): List<String> = seen.filter { it !in current }
    fun stillOn(): List<String> = current.toList()

    fun reset() {
        seen.clear()
        current = emptySet()
    }
}

data class HandoverCard(
    val createdAtMs: Long,
    val previousDriver: String?,
    val tripKm: Double?,
    /** 이번 주행 끝 계기판 거리(km). */
    val odometerKm: Double?,
    val onThenOff: List<String>,
    val stillOn: List<String>,
    val fuelPercent: Int?,
    val evSocPercent: Int?,
    val demo: Boolean = false,
)

object HandoverBuilder {
    fun build(
        nowMs: Long,
        tracker: WarningTracker,
        snapshot: VehicleSnapshot,
        previousDriver: String?,
        tripKm: Double?,
        demo: Boolean = false,
    ) = HandoverCard(
        createdAtMs = nowMs,
        previousDriver = previousDriver,
        tripKm = tripKm,
        odometerKm = snapshot.odometerKm,
        onThenOff = tracker.onThenOff(),
        stillOn = tracker.stillOn(),
        fuelPercent = snapshot.fuelPercent,
        evSocPercent = snapshot.evSocPercent,
        demo = demo,
    )

    /** 카드 요약 한 줄(예: "연료 18% · 켜졌다 꺼진 경고: 워셔액 부족"). */
    fun headline(c: HandoverCard): String {
        val parts = mutableListOf<String>()
        c.fuelPercent?.let { parts += "연료 $it%" }
        c.evSocPercent?.let { parts += "배터리 $it%" }
        parts += when {
            c.stillOn.isNotEmpty() -> "경고 켜짐: " + c.stillOn.joinToString(", ") { PublicWarnings.label(it) }
            c.onThenOff.isNotEmpty() -> "켜졌다 꺼진 경고: " + c.onThenOff.joinToString(", ") { PublicWarnings.label(it) }
            else -> "경고 이력 없음"
        }
        return parts.joinToString(" · ")
    }
}
