package com.moyeon.waketidy.engine

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToLong

/** 국세청 운행기록부 서식의 업무용 사용거리 구분. */
enum class BusinessUse { COMMUTE, GENERAL, NONE }

/** 내릴 때 한 탭으로 고르는 목적. */
enum class Purpose(val label: String, val use: BusinessUse) {
    CLIENT_VISIT("거래처 방문", BusinessUse.GENERAL),
    COMMUTE("출퇴근", BusinessUse.COMMUTE),
    PERSONAL("개인", BusinessUse.NONE),
}

data class Driver(val dept: String, val name: String) {
    val display: String get() = "$dept · $name"
}

data class LogbookEntry(
    val date: LocalDate,
    val driver: Driver,
    val startKm: Double,
    val endKm: Double,
    val purpose: Purpose,
    /** 방문처 등 짧은 메모. 위치·경로는 넣지 않는다. */
    val memo: String = "",
    /** 시연 시나리오가 만든 줄이면 true(비고에 표시). */
    val demo: Boolean = false,
) {
    val distanceKm: Double get() = round1((endKm - startKm).coerceAtLeast(0.0))
    val commuteKm: Double get() = if (purpose.use == BusinessUse.COMMUTE) distanceKm else 0.0
    val generalKm: Double get() = if (purpose.use == BusinessUse.GENERAL) distanceKm else 0.0
    val note: String
        get() = listOfNotNull(
            purpose.label,
            memo.trim().takeIf { it.isNotEmpty() },
            "시연 시나리오".takeIf { demo },
        ).joinToString(" · ")
}

data class TripStart(val startKm: Double, val startMs: Long, val driver: Driver)

data class PendingTrip(val start: TripStart, val endKm: Double, val endMs: Long) {
    val distanceKm: Double get() = round1((endKm - start.startKm).coerceAtLeast(0.0))
    val minutes: Long get() = ((endMs - start.startMs) / 60_000L).coerceAtLeast(0L)
}

/**
 * 주행 시작(P 에서 처음 D·R 로 바뀔 때) 계기판 거리를 적고,
 * 내릴 때(P + 운전석 문 열림) 끝 거리를 적은 뒤, 목적 한 탭으로 운행기록부 한 줄을 만든다.
 */
class TripRecorder {
    var active: TripStart? = null
        private set
    var pending: PendingTrip? = null
        private set

    /** 이 기어 변화에서 주행 시작 거리를 읽어야 하는지. */
    fun shouldStart(prev: GearState, now: GearState): Boolean =
        active == null && pending == null && prev == GearState.PARK && now.isMoving

    fun start(startKm: Double, nowMs: Long, driver: Driver) {
        if (active == null) active = TripStart(startKm, nowMs, driver)
    }

    /** 내릴 때 끝 거리. 진행 중인 주행이 없으면 null. */
    fun end(endKm: Double, nowMs: Long): PendingTrip? {
        val s = active ?: return null
        active = null
        return PendingTrip(s, endKm, nowMs).also { pending = it }
    }

    fun complete(purpose: Purpose, memo: String, date: LocalDate, demo: Boolean = false): LogbookEntry? {
        val p = pending ?: return null
        pending = null
        return LogbookEntry(date, p.start.driver, p.start.startKm, p.endKm, purpose, memo, demo)
    }

    fun reset() {
        active = null
        pending = null
    }
}

object RecentPurposes {
    /** 최근에 쓴 (목적, 메모) 중 메모가 있는 것 3개(중복 없이, 최신 순). */
    fun from(entries: List<LogbookEntry>, max: Int = 3): List<Pair<Purpose, String>> =
        entries.asReversed()
            .filter { it.memo.isNotBlank() }
            .map { it.purpose to it.memo.trim() }
            .distinct()
            .take(max)
}

/**
 * 업무용승용차 운행기록부(국세청 고시 별지 서식) 열 이름을 따른 CSV.
 * 엑셀에서 한글이 깨지지 않게 BOM 을 붙인다.
 */
object LogbookCsv {
    val HEADER = listOf(
        "사용일자(요일)",
        "사용자 부서",
        "사용자 성명",
        "주행 전 계기판의 거리(km)",
        "주행 후 계기판의 거리(km)",
        "주행거리(km)",
        "업무용 사용거리 - 출·퇴근용(km)",
        "업무용 사용거리 - 일반 업무용(km)",
        "비고",
    )

    data class Summary(val totalKm: Double, val businessKm: Double) {
        /** 업무사용비율(%), 소수 첫째 자리. 총주행거리가 0 이면 0. */
        val businessRatioPercent: Double
            get() = if (totalKm <= 0.0) 0.0 else round1(businessKm / totalKm * 100.0)
    }

    fun summary(entries: List<LogbookEntry>): Summary {
        val total = entries.sumOf { it.distanceKm }
        val business = entries.sumOf { it.commuteKm + it.generalKm }
        return Summary(round1(total), round1(business))
    }

    fun row(e: LogbookEntry): List<String> = listOf(
        dateWithDay(e.date),
        e.driver.dept,
        e.driver.name,
        km(e.startKm),
        km(e.endKm),
        km(e.distanceKm),
        km(e.commuteKm),
        km(e.generalKm),
        e.note,
    )

    fun build(entries: List<LogbookEntry>, vehicleName: String = "", plate: String = ""): String {
        val sb = StringBuilder("﻿")
        fun line(cells: List<String>) = sb.append(cells.joinToString(",") { escape(it) }).append("\r\n")
        line(listOf("업무용승용차 운행기록부"))
        line(listOf("차종", vehicleName, "자동차등록번호", plate))
        line(listOf("기록 방식", "계기판 누적 거리(Pleos Vehicle SDK Odometer). 위치·경로는 저장하지 않음"))
        line(HEADER)
        entries.forEach { line(row(it)) }
        val s = summary(entries)
        line(emptyList())
        line(listOf("과세기간 총주행거리(km)", km(s.totalKm)))
        line(listOf("과세기간 업무용 사용거리(km)", km(s.businessKm)))
        line(listOf("업무사용비율(%)", km(s.businessRatioPercent)))
        return sb.toString()
    }

    fun dateWithDay(d: LocalDate): String = "%04d-%02d-%02d(%s)".format(d.year, d.monthValue, d.dayOfMonth, dayKo(d.dayOfWeek))

    fun km(v: Double): String = "%.1f".format(java.util.Locale.US, v)

    fun escape(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell

    private fun dayKo(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }
}

internal fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0
