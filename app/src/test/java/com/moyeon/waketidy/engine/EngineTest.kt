package com.moyeon.waketidy.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private const val MIN = 60_000L

class DrivingTimerTest {
    @Test
    fun accumulatesOnlyWhileMoving() {
        val t = DrivingTimer(limitMs = 100 * MIN)
        t.update(GearState.PARK, 0)
        t.tick(10 * MIN)
        assertEquals(0L, t.drivingMs)
        t.update(GearState.DRIVE, 10 * MIN)
        t.tick(60 * MIN)
        assertEquals(50 * MIN, t.drivingMs)
        t.update(GearState.PARK, 70 * MIN)
        assertEquals(60 * MIN, t.drivingMs)
        t.tick(75 * MIN) // 5분 정차는 쉼이 아님
        assertEquals(60 * MIN, t.drivingMs)
    }

    @Test
    fun suggestsOnlyInParkAfterLimit() {
        val t = DrivingTimer(limitMs = 100 * MIN)
        t.update(GearState.DRIVE, 0)
        t.tick(101 * MIN)
        assertTrue(t.isDue)
        assertFalse("주행 중엔 제안하지 않는다", t.shouldSuggestRest)
        t.update(GearState.PARK, 101 * MIN)
        assertTrue(t.shouldSuggestRest)
    }

    @Test
    fun longParkResets() {
        val t = DrivingTimer(limitMs = 100 * MIN, resetAfterParkMs = 15 * MIN)
        t.update(GearState.DRIVE, 0)
        t.update(GearState.PARK, 90 * MIN)
        t.tick(104 * MIN)
        assertEquals(90 * MIN, t.drivingMs)
        t.tick(105 * MIN)
        assertEquals(0L, t.drivingMs)
    }
}

class WakeScheduleTest {
    @Test
    fun threeStagesGetStronger() {
        val m = setOf(WakeMethod.AMBIENT, WakeMethod.FAN, WakeMethod.SEAT_HEAT)
        val stages = WakeSchedule.stages(m)
        assertEquals(listOf(0, 30, 60), stages.map { it.startSec })
        val ambient = stages.map { s -> s.actions.filterIsInstance<WakeAction.Ambient>().single().fraction }
        assertTrue(ambient[0] < ambient[1] && ambient[1] < ambient[2])
        val fan = stages.map { s -> s.actions.filterIsInstance<WakeAction.Fan>().single().fraction }
        assertTrue(fan[0] < fan[1] && fan[1] < fan[2])
        assertTrue("1단계는 소리 없이", stages[0].actions.none { it is WakeAction.Tone })
        assertTrue(stages[2].actions.any { it is WakeAction.Tone })
    }

    @Test
    fun readingLightJoinsAtStage2WhenAmbientChosen() {
        val m = setOf(WakeMethod.AMBIENT, WakeMethod.READING)
        assertTrue(WakeSchedule.actionsFor(m, 1).none { it is WakeAction.ReadingLight })
        assertTrue(WakeSchedule.actionsFor(m, 2).contains(WakeAction.ReadingLight(true)))
        assertTrue(WakeSchedule.actionsFor(setOf(WakeMethod.READING), 1).contains(WakeAction.ReadingLight(true)))
    }

    @Test
    fun levelAtCapsAtThree() {
        assertEquals(1, WakeSchedule.levelAt(0))
        assertEquals(1, WakeSchedule.levelAt(29))
        assertEquals(2, WakeSchedule.levelAt(30))
        assertEquals(3, WakeSchedule.levelAt(60))
        assertEquals(3, WakeSchedule.levelAt(600))
    }

    @Test
    fun endActionsTurnThingsOff() {
        val end = WakeSchedule.endActions(setOf(WakeMethod.READING, WakeMethod.SEAT_HEAT, WakeMethod.SEAT_VENT))
        assertTrue(end.contains(WakeAction.ReadingLight(false)))
        assertTrue(end.contains(WakeAction.SeatHeat(0f)))
        assertTrue(end.contains(WakeAction.SeatVent(0f)))
    }
}

class RestFlowTest {
    private fun drive(flow: RestFlow, minutes: Long) {
        flow.tick(GearState.DRIVE, 0)
        flow.tick(GearState.DRIVE, minutes * MIN)
    }

    @Test
    fun fullCycle() {
        val flow = RestFlow(DrivingTimer(100 * MIN), setOf(WakeMethod.AMBIENT))
        drive(flow, 100)
        assertEquals(RestState.Idle, flow.state)
        flow.tick(GearState.PARK, 100 * MIN)
        assertTrue(flow.state is RestState.Suggested)

        flow.chooseRest(10, 101 * MIN)
        assertTrue(flow.tick(GearState.PARK, 105 * MIN).isEmpty())
        val e1 = flow.tick(GearState.PARK, 111 * MIN)
        assertEquals(1, (e1.single() as RestEvent.StageEntered).stage.level)

        val e2 = flow.tick(GearState.PARK, 111 * MIN + 65_000)
        assertEquals(listOf(2, 3), e2.map { (it as RestEvent.StageEntered).stage.level })

        val done = flow.awake(111 * MIN + 70_000).single() as RestEvent.WakeEnded
        assertEquals(3, done.record.levelReached)
        assertEquals(70L, done.record.secondsToWake)
        assertEquals(0L, flow.timer.drivingMs)
        assertTrue(flow.state is RestState.Recovering)

        assertEquals(listOf(RestEvent.RecoveryDone), flow.tick(GearState.PARK, 111 * MIN + 70_000 + 2 * MIN))
        assertEquals(RestState.Idle, flow.state)
    }

    @Test
    fun drivingAwayCancelsRest() {
        val flow = RestFlow(DrivingTimer(1 * MIN))
        drive(flow, 2)
        flow.tick(GearState.PARK, 2 * MIN)
        flow.chooseRest(5, 2 * MIN)
        assertEquals(listOf(RestEvent.Cancelled), flow.tick(GearState.DRIVE, 3 * MIN))
        assertEquals(RestState.Idle, flow.state)
    }

    @Test
    fun dismissWaitsForNextStop() {
        val flow = RestFlow(DrivingTimer(1 * MIN))
        drive(flow, 2)
        flow.tick(GearState.PARK, 2 * MIN)
        flow.dismiss()
        flow.tick(GearState.PARK, 3 * MIN)
        assertEquals(RestState.Idle, flow.state)
        flow.tick(GearState.DRIVE, 3 * MIN)
        flow.tick(GearState.PARK, 4 * MIN)
        assertTrue(flow.state is RestState.Suggested)
    }
}

class TidyTest {
    @Test
    fun exitFiresOncePerStop() {
        val d = ExitDetector()
        assertFalse(d.update(GearState.DRIVE, true))
        assertTrue(d.update(GearState.PARK, true))
        assertFalse(d.update(GearState.PARK, true))
        assertFalse(d.update(GearState.PARK, false))
        assertFalse(d.update(GearState.PARK, true))
        d.update(GearState.DRIVE, false)
        assertTrue(d.update(GearState.PARK, true))
    }

    @Test
    fun noExitWithoutPark() {
        val d = ExitDetector()
        assertFalse(d.update(GearState.UNKNOWN, true))
        assertFalse(d.update(GearState.NEUTRAL, true))
    }

    @Test
    fun planListsOpenWindowsLightsRearSeatAndLock() {
        val s = VehicleSnapshot(
            gear = GearState.PARK,
            windows = mapOf(Areas.DRIVER to 0, Areas.PASSENGER to 30, Areas.REAR_LEFT to null),
            readingLights = mapOf(Areas.REAR_LEFT to true, Areas.DRIVER to false),
            rearOccupied = mapOf(Areas.REAR_RIGHT to true),
            driverDoorLocked = false,
        )
        val items = TidyPlanner.plan(s)
        assertEquals(
            listOf(TidyKind.REAR_SEAT_CHECK, TidyKind.CLOSE_WINDOW, TidyKind.READING_LIGHT_OFF, TidyKind.LOCK_DOORS),
            items.map { it.kind },
        )
        assertEquals(Areas.PASSENGER, items[1].area)
        assertFalse("뒷좌석은 알림만", items[0].actionable)
        assertTrue(items.drop(1).all { it.actionable })
    }

    @Test
    fun nothingToTidyWhenAllClosedAndLocked() {
        val s = VehicleSnapshot(windows = mapOf(Areas.DRIVER to 0), readingLights = mapOf(Areas.DRIVER to false), driverDoorLocked = true)
        assertTrue(TidyPlanner.plan(s).isEmpty())
    }
}

class LogbookTest {
    private val driver = Driver("영업1팀", "사원 B")

    @Test
    fun tripStartsOnFirstLeaveFromPark() {
        val r = TripRecorder()
        assertFalse(r.shouldStart(GearState.UNKNOWN, GearState.DRIVE))
        assertTrue(r.shouldStart(GearState.PARK, GearState.DRIVE))
        r.start(12_340.0, 0, driver)
        assertFalse("이미 시작한 주행", r.shouldStart(GearState.PARK, GearState.DRIVE))
    }

    @Test
    fun oneRowFromOdometer() {
        val r = TripRecorder()
        r.start(12_340.0, 0, driver)
        val p = r.end(12_371.44, 42 * MIN)!!
        assertEquals(31.4, p.distanceKm, 1e-9)
        assertEquals(42L, p.minutes)
        val e = r.complete(Purpose.CLIENT_VISIT, "한빛상사", LocalDate.of(2026, 11, 14))!!
        assertEquals(31.4, e.generalKm, 1e-9)
        assertEquals(0.0, e.commuteKm, 1e-9)
        assertEquals(
            listOf("2026-11-14(토)", "영업1팀", "사원 B", "12340.0", "12371.4", "31.4", "0.0", "31.4", "거래처 방문 · 한빛상사"),
            LogbookCsv.row(e),
        )
        assertNull(r.pending)
    }

    @Test
    fun endWithoutStartGivesNothing() {
        assertNull(TripRecorder().end(100.0, 0))
    }

    @Test
    fun csvHeaderSummaryAndEscaping() {
        val d = LocalDate.of(2026, 11, 14)
        val entries = listOf(
            LogbookEntry(d, driver, 100.0, 130.0, Purpose.CLIENT_VISIT, "A사, 본사"),
            LogbookEntry(d, driver, 130.0, 140.0, Purpose.COMMUTE),
            LogbookEntry(d, driver, 140.0, 150.0, Purpose.PERSONAL, demo = true),
        )
        val csv = LogbookCsv.build(entries)
        assertTrue(csv.startsWith("﻿"))
        assertTrue(csv.contains(LogbookCsv.HEADER.joinToString(",")))
        assertTrue(csv.contains("\"거래처 방문 · A사, 본사\""))
        assertTrue(csv.contains("개인 · 시연 시나리오"))
        val s = LogbookCsv.summary(entries)
        assertEquals(50.0, s.totalKm, 1e-9)
        assertEquals(40.0, s.businessKm, 1e-9)
        assertEquals(80.0, s.businessRatioPercent, 1e-9)
        assertTrue(csv.contains("업무사용비율(%),80.0"))
    }

    @Test
    fun recentPurposesDistinctNewestFirst() {
        val d = LocalDate.of(2026, 11, 14)
        val e = listOf("가", "나", "가", "", "다", "라").map { LogbookEntry(d, driver, 0.0, 1.0, Purpose.CLIENT_VISIT, it) }
        assertEquals(listOf("라", "다", "가"), RecentPurposes.from(e).map { it.second })
    }
}

class HandoverTest {
    private val washer = PublicWarnings.WASHER_FLUID_LOW
    private val oil = PublicWarnings.ENGINE_OIL_LOW

    @Test
    fun warningOnThenOff() {
        val t = WarningTracker()
        t.update(setOf(washer, "SOMETHING_ELSE"))
        t.update(setOf(oil))
        t.update(setOf(oil))
        assertEquals(listOf(washer), t.onThenOff())
        assertEquals(listOf(oil), t.stillOn())
    }

    @Test
    fun headline() {
        val t = WarningTracker().apply { update(setOf(washer)); update(emptySet()) }
        val card = HandoverBuilder.build(0, t, VehicleSnapshot(fuelPercent = 18, odometerKm = 12_345.0), "영업1팀 · 사원 B", 31.4)
        assertEquals("연료 18% · 켜졌다 꺼진 경고: 워셔액 부족", HandoverBuilder.headline(card))
        assertEquals(12_345.0, card.odometerKm!!, 1e-9)
    }
}

/** 시연 시나리오를 엔진에 그대로 흘려서 30초 시연의 결과가 나오는지 확인한다. */
class DemoScenarioTest {
    @Test
    fun scenarioDrivesWholeStory() {
        val engine = WakeTidyEngine(limitMinutes = 100, methods = setOf(WakeMethod.AMBIENT, WakeMethod.FAN, WakeMethod.SEAT_HEAT))
        val driver = Driver("영업1팀", "사원 B")
        var clock = 1_000_000L
        var snap = VehicleSnapshot()
        val wakeLevels = mutableListOf<Int>()
        var exitItems: List<TidyItem> = emptyList()
        var result: Pair<LogbookEntry, HandoverCard>? = null
        var lastAt = 0L

        fun handle(outs: List<EngineOutput>) = outs.forEach { o ->
            when (o) {
                is EngineOutput.Wake -> wakeLevels += o.stage.level
                EngineOutput.NeedStartOdometer -> engine.startTrip(snap.odometerKm!!, clock, driver)
                is EngineOutput.ExitDetected -> {
                    exitItems = o.items
                    if (o.needEndOdometer) engine.endTrip(snap.odometerKm!!, clock)
                }
                else -> Unit
            }
        }

        for (step in DemoScenario.steps(100)) {
            clock += (step.atMs - lastAt) + step.advanceClockMs
            lastAt = step.atMs
            step.change?.let { snap = it(snap) }
            handle(engine.onSnapshot(snap, clock))
            when (step.tap) {
                DemoTap.CHOOSE_REST_10 -> {
                    assertTrue("P단에서 쉼 제안", engine.rest.state is RestState.Suggested)
                    engine.chooseRest(10, clock)
                }
                DemoTap.AWAKE -> handle(engine.awake(clock))
                DemoTap.TIDY_ALL -> {
                    snap = snap.copy(
                        windows = snap.windows.mapValues { 0 },
                        readingLights = snap.readingLights.mapValues { false },
                        driverDoorLocked = true,
                    )
                    handle(engine.onSnapshot(snap, clock))
                }
                DemoTap.PURPOSE_CLIENT_VISIT -> result = engine.completeTrip(Purpose.CLIENT_VISIT, "", LocalDate.of(2026, 11, 14), clock, demo = true)
                null -> Unit
            }
        }

        assertEquals(listOf(1, 2, 3), wakeLevels)
        assertEquals(
            listOf(TidyKind.REAR_SEAT_CHECK, TidyKind.CLOSE_WINDOW, TidyKind.READING_LIGHT_OFF, TidyKind.LOCK_DOORS),
            exitItems.map { it.kind },
        )
        assertEquals("정리 후에는 뒷좌석 확인만 남는다", listOf(TidyKind.REAR_SEAT_CHECK), engine.exitItems.map { it.kind })
        assertNotNull(result)
        val (entry, card) = result!!
        assertEquals(DemoScenario.START_KM, entry.startKm, 1e-9)
        assertEquals(DemoScenario.TRIP_KM, entry.distanceKm, 1e-9)
        assertTrue(entry.note.contains("시연 시나리오"))
        assertEquals(listOf(PublicWarnings.WASHER_FLUID_LOW), card.onThenOff)
        assertEquals(DemoScenario.START_KM + DemoScenario.TRIP_KM, card.odometerKm!!, 1e-9)
        assertEquals(18, card.fuelPercent)
    }
}
