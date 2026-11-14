package com.moyeon.waketidy.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.PowerDownWarningIndicatorListener
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.moyeon.waketidy.engine.Areas
import com.moyeon.waketidy.engine.GearState
import com.moyeon.waketidy.engine.TidyItem
import com.moyeon.waketidy.engine.TidyKind
import com.moyeon.waketidy.engine.VehicleSnapshot
import com.moyeon.waketidy.engine.WakeAction
import com.moyeon.waketidy.engine.PublicWarnings
import kotlin.math.roundToInt

data class SdkLogEntry(val atMs: Long, val api: String, val ok: Boolean, val detail: String)

/**
 * Pleos Vehicle SDK 2.3.2 어댑터. 실제 SDK 를 호출하고, 결과를 [VehicleSnapshot] 으로 바꿔 넘긴다.
 * 모든 콜백은 메인 스레드로 옮긴다. 호출 실패는 화면을 죽이지 않고 [SdkLogEntry] 로 남긴다.
 */
class VehicleGateway(
    context: Context,
    private val onLog: (SdkLogEntry) -> Unit,
    private val onSnapshot: (VehicleSnapshot) -> Unit,
    /** 기어(AAOS GEAR_SELECTION)를 읽을 수 있는지. 메인 스레드로 알린다. */
    private val onGearSupported: (Boolean) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    /** SDK 호출은 바인더 왕복이 길어(에뮬레이터에서 등록 전체 약 5초) 화면 스레드를 막지 않도록 따로 돌린다. */
    private val ioThread = android.os.HandlerThread("vehicle-sdk").apply { start() }
    private val io = Handler(ioThread.looper)
    private val appContext = context.applicationContext
    private val vehicle = Vehicle(appContext)
    private var car: Car? = null
    private val unregisters = mutableListOf<() -> Unit>()
    private var initialized = false

    var real = VehicleSnapshot()
        private set

    // ---- 차가 알려 준 값의 범위(능력 조회). 못 읽으면 추정 기본값 ----
    private data class Bounds(val area: SeatArea, val min: Int, val max: Int)

    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private var ambient = Bounds(driverSeat, 0, 100)
    private var fan = Bounds(driverSeat, 1, 8)
    private var seatHeat = Bounds(driverSeat, 0, 3)
    private var seatVent = Bounds(driverSeat, 0, 3)
    private val windowClosed = mutableMapOf<String, Int>()
    @Volatile private var lockAreas: List<DoorArea> = emptyList()

    // 공개 경고 신호 → activeWarnings
    private val publicWarnings = mutableSetOf<String>()

    // 깨우기 전에 읽어 둔 값(깨운 뒤 되돌리기)
    private var prevAmbient: Int? = null
    private var prevFanSpeed: Int? = null
    private var prevFanDirection: FanDirectionType? = null
    private var prevHvacPower: Boolean? = null
    private var powerArea: SeatArea? = null
    private var prevHvacAuto: Boolean? = null
    private var wakeStarted = false
    private var tone: ToneGenerator? = null

    fun start(): Boolean {
        if (initialized) return true
        return try {
            vehicle.initialize()
            initialized = true
            log("Vehicle.initialize", true, "SDK ${com.moyeon.waketidy.BuildConfig.VEHICLE_SDK_VERSION}")
            // 서비스 연결 직후 바로 등록하면 실패하는 경우가 있어 한 박자 쉬고 등록한다.
            io.postDelayed({ readCapabilities(); subscribeAll() }, 600)
            true
        } catch (e: Exception) {
            log("Vehicle.initialize", false, e.describe())
            false
        }
    }

    fun stop() {
        val regs = unregisters.toList()
        unregisters.clear()
        tone?.release()
        tone = null
        val wasInit = initialized
        initialized = false
        io.post {
            regs.forEach { runCatching(it) }
            if (wasInit) runCatching { vehicle.release() }
            car?.let { runCatching { it.disconnect() } }
            car = null
            ioThread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ 읽기·구독

    private fun subscribeAll() {
        subscribeGear()

        // 운전석 문 위치 + 잠금
        val door = vehicle.getDoor()
        val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
        val doorListener = object : DoorPositionListener {
            override fun onPositionUpdated(p0: Int?) = post { update { it.copy(driverDoorPosition = p0) } }
            override fun onFailed(p0: Exception) = post { log("Door.registerDoorPosition(update)", false, p0.describe()) }
        }
        sdk("Door.getCurrentDoorPosition(ROW_1_LEFT)") { ok, fail -> door.getCurrentDoorPosition(driverDoor, { v -> ok(v); update { it.copy(driverDoorPosition = v) } }, fail) }
        register("Door.registerDoorPosition(ROW_1_LEFT)", { door.registerDoorPosition(driverDoor, doorListener) }, { door.unregisterDoorPosition(driverDoor, doorListener) })
        // 잠금은 차마다 지원 영역이 다르다(에뮬레이터는 ROW_1_LEFT 단독 영역을 지원하지 않음) → 능력 조회로 영역을 찾는다.
        val lockListener = object : DoorLockStateListener {
            override fun onDoorLockStateUpdated(p0: Boolean?) = post { update { it.copy(driverDoorLocked = p0) } }
            override fun onFailed(p0: Exception) = post { log("Door.registerDoorLockState(update)", false, p0.describe()) }
        }
        sdk("Door.checkDoorLockCapabilities") { ok, fail ->
            door.checkDoorLockCapabilities({ areas: List<DoorArea> ->
                ok(areas.joinToString { a -> a.values.joinToString("+") })
                io.post {
                    // 문 잠금만(보닛 HOOD·트렁크 REAR 제외)
                    lockAreas = areas.filter { a -> a.values.any { it.name.startsWith("ROW_") } }
                    val first = lockAreas.firstOrNull { DoorValue.ROW_1_LEFT in it.values } ?: lockAreas.firstOrNull() ?: return@post
                    val name = first.values.joinToString("+")
                    sdk("Door.getDoorLockState($name)") { ok2, fail2 -> door.getDoorLockState(first, { v -> ok2(v); update { it.copy(driverDoorLocked = v) } }, fail2) }
                    register("Door.registerDoorLockState($name)", { door.registerDoorLockState(first, lockListener) }, { door.unregisterDoorLockState(first, lockListener) })
                }
            }, fail)
        }

        // 창 4개
        val window = vehicle.getWindow()
        for (code in Areas.CABIN) {
            val area = WindowArea(WindowValue.valueOf(code))
            val l = object : WindowPositionListener {
                override fun onWindowPositionUpdated(p0: Int?) = post { update { it.copy(windows = it.windows + (code to p0)) } }
                override fun onFailed(p0: Exception) = post { log("Window.registerWindowPosition($code)(update)", false, p0.describe()) }
            }
            sdk("Window.getCurrentWindowPosition($code)") { ok, fail -> window.getCurrentWindowPosition(area, { v -> ok(v); update { it.copy(windows = it.windows + (code to v)) } }, fail) }
            register("Window.registerWindowPosition($code)", { window.registerWindowPosition(area, l) }, { window.unregisterWindowPosition(area, l) })
        }

        // 독서등 4자리
        val light = vehicle.getLight()
        for (code in Areas.CABIN) {
            val area = SeatArea(SeatValue.valueOf(code))
            val l = object : LightStateListener {
                override fun onLightStateUpdated(p0: LightState?) = post { update { it.copy(readingLights = it.readingLights + (code to (p0 == LightState.ON))) } }
                override fun onFailed(p0: Exception) = post { log("Light.registerReadingLightState($code)(update)", false, p0.describe()) }
            }
            sdk("Light.getCurrentReadingLightState($code)") { ok, fail -> light.getCurrentReadingLightState(area, { v -> ok(v?.name); update { it.copy(readingLights = it.readingLights + (code to (v == LightState.ON))) } }, fail) }
            register("Light.registerReadingLightState($code)", { light.registerReadingLightState(area, l) }, { light.unregisterReadingLightState(area, l) })
        }

        // 뒷좌석 착석
        val seat = vehicle.getSeat()
        for (code in Areas.REAR) {
            val area = SeatArea(SeatValue.valueOf(code))
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(p0: Boolean?) = post { update { it.copy(rearOccupied = it.rearOccupied + (code to p0)) } }
                override fun onFailed(p0: Exception) = post { log("Seat.registerSeatOccupancy($code)(update)", false, p0.describe()) }
            }
            sdk("Seat.getCurrentSeatOccupancy($code)") { ok, fail -> seat.getCurrentSeatOccupancy(area, { v -> ok(v); update { it.copy(rearOccupied = it.rearOccupied + (code to v)) } }, fail) }
            register("Seat.registerSeatOccupancy($code)", { seat.registerSeatOccupancy(area, l) }, { seat.unregisterSeatOccupancy(area, l) })
        }

        val safety = vehicle.getSafety()
        // 다음 사람 카드용 경고: 공개 권한(CAR_SAFETY)으로 읽히는 엔진오일·브레이크액·워셔액·저전력만 쓴다.
        // 경고등 목록(Safety.*Telltales)·타이어 공기압(Tire.*TirePressure)은 PRIVATE_CAR_SAFETY 권한이 필요해 호출하지 않는다.
        val oilListener = object : OilLevelStateListener {
            override fun onOilLevelUpdated(p0: OilLevelState?) = post { setWarning(PUB_ENGINE_OIL, p0.isLow()) }
            override fun onFailed(p0: Exception) = post { log("Safety.registerEngineOilLevelState(update)", false, p0.describe()) }
        }
        sdk("Safety.getEngineOilLevelState") { ok, fail -> safety.getEngineOilLevelState({ v -> ok(v?.name); post { setWarning(PUB_ENGINE_OIL, v.isLow()) } }, fail) }
        register("Safety.registerEngineOilLevelState", { safety.registerEngineOilLevelState(oilListener) }, { safety.unregisterEngineOilLevelState(oilListener) })
        val brakeListener = object : OilLevelStateListener {
            override fun onOilLevelUpdated(p0: OilLevelState?) = post { setWarning(PUB_BRAKE_FLUID, p0.isLow()) }
            override fun onFailed(p0: Exception) = post { log("Safety.registerBrakeFluidLevelState(update)", false, p0.describe()) }
        }
        sdk("Safety.getBrakeFluidLevelState") { ok, fail -> safety.getBrakeFluidLevelState({ v -> ok(v?.name); post { setWarning(PUB_BRAKE_FLUID, v.isLow()) } }, fail) }
        register("Safety.registerBrakeFluidLevelState", { safety.registerBrakeFluidLevelState(brakeListener) }, { safety.unregisterBrakeFluidLevelState(brakeListener) })
        val washerListener = object : LowWasherFluidWaringListener {
            override fun onLowWasherFluidWaringUpdated(p0: Boolean?) = post { setWarning(PUB_WASHER, p0 == true) }
            override fun onFailed(p0: Exception) = post { log("Safety.registerLowWasherFluidWaringState(update)", false, p0.describe()) }
        }
        sdk("Safety.hasLowWasherFluidWarning") { ok, fail -> safety.hasLowWasherFluidWarning({ v -> ok(v); post { setWarning(PUB_WASHER, v == true) } }, fail) }
        register("Safety.registerLowWasherFluidWaringState", { safety.registerLowWasherFluidWaringState(washerListener) }, { safety.unregisterLowWasherFluidWaringState(washerListener) })
        val powerListener = object : PowerDownWarningIndicatorListener {
            override fun onPowerDownWarningIndicatorUpdated(p0: Boolean?) = post { setWarning(PUB_POWER_DOWN, p0 == true) }
            override fun onFailed(p0: Exception) = post { log("Safety.registerPowerDownWarningIndicator(update)", false, p0.describe()) }
        }
        sdk("Safety.hasPowerDownWarningIndicator") { ok, fail -> safety.hasPowerDownWarningIndicator({ v -> ok(v); post { setWarning(PUB_POWER_DOWN, v == true) } }, fail) }
        register("Safety.registerPowerDownWarningIndicator", { safety.registerPowerDownWarningIndicator(powerListener) }, { safety.unregisterPowerDownWarningIndicator(powerListener) })

        // 연료·배터리
        val fuel = vehicle.getFuel()
        val fuelListener = object : FuelPercentListener {
            override fun onFuelPercentUpdated(p0: Int?) = post { update { it.copy(fuelPercent = p0) } }
            override fun onFailed(p0: Exception) = post { log("Fuel.registerFuelPercent(update)", false, p0.describe()) }
        }
        sdk("Fuel.getFuelPercent") { ok, fail -> fuel.getFuelPercent({ v -> ok(v); update { it.copy(fuelPercent = v) } }, fail) }
        register("Fuel.registerFuelPercent", { fuel.registerFuelPercent(fuelListener) }, { fuel.unregisterFuelPercent(fuelListener) })
        val ev = vehicle.getEvBattery()
        val socListener = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(p0: Int?) = post { update { it.copy(evSocPercent = p0) } }
            override fun onFailed(p0: Exception) = post { log("EvBattery.registerEvBatteryStateOfCharge(update)", false, p0.describe()) }
        }
        sdk("EvBattery.getEvBatteryStateOfCharge") { ok, fail -> ev.getEvBatteryStateOfCharge({ v -> ok(v); update { it.copy(evSocPercent = v) } }, fail) }
        register("EvBattery.registerEvBatteryStateOfCharge", { ev.registerEvBatteryStateOfCharge(socListener) }, { ev.unregisterEvBatteryStateOfCharge(socListener) })

        readOdometer { }
    }

    private fun readCapabilities() {
        val light = vehicle.getLight()
        sdk("Light.checkAmbientLightBrightnessCapabilities") { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities({ _: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
                pickBounds(m)?.let { post { ambient = it } }
                ok(m.describeBounds())
            }, fail)
        }
        val hvac = vehicle.getHVAC()
        sdk("HVAC.checkHvacFanSpeedCapabilities") { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities({ m: Map<SeatArea, AreaBounds<Int>> ->
                pickBounds(m)?.let { post { fan = it } }
                ok(m.describeBounds())
            }, fail)
        }
        sdk("HVAC.checkHvacPowerCapabilities") { ok, fail ->
            hvac.checkHvacPowerCapabilities({ areas: List<SeatArea> ->
                ok(areas.joinToString { a -> a.values.joinToString("+") })
                post { powerArea = areas.firstOrNull() }
            }, fail)
        }
        val seat = vehicle.getSeat()
        sdk("Seat.checkSeatTemperatureCapabilities") { ok, fail ->
            seat.checkSeatTemperatureCapabilities({ m: Map<SeatArea, AreaBounds<Int>> ->
                pickBounds(m, preferDriver = true)?.let { post { seatHeat = it } }
                ok(m.describeBounds())
            }, fail)
        }
        sdk("Seat.checkSeatVentilationCapabilities") { ok, fail ->
            seat.checkSeatVentilationCapabilities({ m: Map<SeatArea, AreaBounds<Int>> ->
                pickBounds(m, preferDriver = true)?.let { post { seatVent = it } }
                ok(m.describeBounds())
            }, fail)
        }
        val window = vehicle.getWindow()
        sdk("Window.checkWindowPositionCapabilities") { ok, fail ->
            window.checkWindowPositionCapabilities({ m: Map<WindowArea, AreaBounds<Int>> ->
                post { m.forEach { (a, b) -> a.values.forEach { v -> windowClosed[v.name] = 0.coerceIn(b.minValue, b.maxValue) } } }
                ok(m.entries.joinToString { (a, b) -> "${a.values.joinToString("+")}=${b.minValue}..${b.maxValue}" })
            }, fail)
        }
    }

    /** 계기판 누적 거리(km). 실패하면 null. */
    fun readOdometer(cbAny: (Double?) -> Unit) {
        val cb: (Double?) -> Unit = { v -> post { cbAny(v) } }
        val odo = runCatching { vehicle.getOdometer() }.getOrNull()
        if (odo == null || !initialized) {
            log("Odometer.getCurrentDistanceDriven", false, "SDK 초기화 안 됨")
            cb(null)
            return
        }
        sdk("Odometer.getCurrentDistanceDriven", onFail = { cb(null) }) { ok, fail ->
            odo.getCurrentDistanceDriven({ v ->
                ok(v)
                val km = v?.toDouble()
                update { it.copy(odometerKm = km) }
                cb(km)
            }, fail)
        }
    }

    // ------------------------------------------------------------------ 쓰기: 원탭 정리

    /** 정리 한 항목을 실제 차에 쓴다. [cb] 는 (성공, 설명). */
    fun tidy(item: TidyItem, cbAny: (Boolean, String) -> Unit) {
        val cb: (Boolean, String) -> Unit = { a, b -> post { cbAny(a, b) } }
        if (!initialized) {
            cb(false, "SDK 초기화 안 됨")
            return
        }
        when (item.kind) {
            TidyKind.CLOSE_WINDOW -> {
                val code = item.area ?: return cb(false, "영역 없음")
                val closed = windowClosed[code] ?: 0
                val api = "Window.setWindowPosition($code, $closed)"
                sdk(api, onFail = { cb(false, it) }) { ok, fail ->
                    vehicle.getWindow().setWindowPosition(WindowArea(WindowValue.valueOf(code)), closed, { _, v -> ok(v); cb(true, "창 닫음") }, fail)
                }
            }
            TidyKind.READING_LIGHT_OFF -> {
                val code = item.area ?: return cb(false, "영역 없음")
                sdk("Light.setReadingLightSwitch($code, OFF)", onFail = { cb(false, it) }) { ok, fail ->
                    vehicle.getLight().setReadingLightSwitch(SeatArea(SeatValue.valueOf(code)), LightSwitch.OFF, { v -> ok(v?.name); cb(true, "독서등 끔") }, fail)
                }
            }
            TidyKind.LOCK_DOORS -> lockAllDoors(cb)
            TidyKind.REAR_SEAT_CHECK -> cb(true, "알림만")
        }
    }

    private fun lockAllDoors(cb: (Boolean, String) -> Unit) {
        val doors = lockAreas.ifEmpty {
            listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT).map { DoorArea(it) }
        }
        var done = 0
        var okCount = 0
        val errors = mutableListOf<String>()
        fun finishOne() {
            done++
            if (done == doors.size) {
                if (okCount == doors.size) cb(true, "잠금 완료(문 ${doors.sumOf { d -> d.values.size }}개)")
                else cb(okCount > 0, "문 ${okCount}/${doors.size} 잠금" + if (errors.isNotEmpty()) " · ${errors.first()}" else "")
            }
        }
        for (d in doors) {
            sdk("Door.setDoorLock(${d.values.joinToString("+")}, true)", onFail = { post { errors += it; finishOne() } }) { ok, fail ->
                vehicle.getDoor().setDoorLock(d, true, { _, v -> ok(v); post { okCount++; finishOne() } }, fail)
            }
        }
    }

    // ------------------------------------------------------------------ 쓰기: 깨우기

    fun applyWake(actions: List<WakeAction>) {
        if (!wakeStarted) {
            wakeStarted = true
            rememberBeforeWake()
        }
        actions.forEach(::applyAction)
    }

    /** 깨우기 끝: 끄기 동작 + 무드등·팬을 깨우기 전 값으로. */
    fun endWake(actions: List<WakeAction>) {
        actions.forEach(::applyAction)
        if (wakeStarted) {
            val light = vehicle.getLight()
            val hvac = vehicle.getHVAC()
            prevAmbient?.let { v -> sdk("Light.setAmbientLightBrightness(되돌림 $v)") { ok, fail -> light.setAmbientLightBrightness(ambient.area, v, { _, r -> ok(r) }, fail) } }
            prevFanSpeed?.let { v -> sdk("HVAC.setHvacFanSpeed(되돌림 $v)") { ok, fail -> hvac.setHvacFanSpeed(fan.area, v, { _, r -> ok(r) }, fail) } }
            prevFanDirection?.let { v -> sdk("HVAC.setHvacFanDirection(되돌림 ${v.name})") { ok, fail -> hvac.setHvacFanDirection(fan.area, v, { _, r -> ok(r?.name) }, fail) } }
            if (prevHvacAuto == true) sdk("HVAC.setHvacAuto(되돌림 true)") { ok, fail -> hvac.setHvacAuto(fan.area, true, { _, r -> ok(r) }, fail) }
            val pArea = powerArea
            if (prevHvacPower == false && pArea != null) sdk("HVAC.setHvacPower(되돌림 false)") { ok, fail -> hvac.setHvacPower(pArea, false, { _, r -> ok(r) }, fail) }
        }
        wakeStarted = false
        prevAmbient = null
        prevFanSpeed = null
        prevFanDirection = null
        prevHvacPower = null
        prevHvacAuto = null
    }

    private fun rememberBeforeWake() {
        if (!initialized) return
        sdk("Light.getAmbientLightBrightness(깨우기 전)") { ok, fail -> vehicle.getLight().getAmbientLightBrightness(ambient.area, { v -> ok(v); post { prevAmbient = v } }, fail) }
        sdk("HVAC.getHvacFanSpeed(깨우기 전)") { ok, fail -> vehicle.getHVAC().getHvacFanSpeed(fan.area, { v -> ok(v); post { prevFanSpeed = v } }, fail) }
        powerArea?.let { pa -> sdk("HVAC.getHvacPowerState(깨우기 전)") { ok, fail -> vehicle.getHVAC().getHvacPowerState(pa, { v -> ok(v); post { prevHvacPower = v } }, fail) } }
        sdk("HVAC.getHvacFanDirection(깨우기 전)") { ok, fail -> vehicle.getHVAC().getHvacFanDirection(fan.area, { v -> ok(v?.name); post { prevFanDirection = v } }, fail) }
    }

    private fun applyAction(a: WakeAction) {
        if (a is WakeAction.Tone) {
            beep(a.beeps)
            return
        }
        if (!initialized) {
            log(a.toString(), false, "SDK 초기화 안 됨")
            return
        }
        when (a) {
            is WakeAction.Ambient -> {
                val light = vehicle.getLight()
                val v = scale(ambient, a.fraction)
                sdk("Light.setAmbientLightSwitch(ON)") { ok, fail -> light.setAmbientLightSwitch(ambient.area, LightSwitch.ON, { _, r -> ok(r?.name) }, fail) }
                sdk("Light.setAmbientLightBrightness($v)") { ok, fail -> light.setAmbientLightBrightness(ambient.area, v, { _, r -> ok(r) }, fail) }
            }
            is WakeAction.ReadingLight -> {
                val sw = if (a.on) LightSwitch.ON else LightSwitch.OFF
                sdk("Light.setReadingLightSwitch(ROW_1_LEFT, ${sw.name})") { ok, fail -> vehicle.getLight().setReadingLightSwitch(driverSeat, sw, { r -> ok(r?.name) }, fail) }
            }
            is WakeAction.Fan -> {
                val hvac = vehicle.getHVAC()
                val v = scale(fan, a.fraction)
                // 공조가 꺼져 있으면 팬 속도·풍향 쓰기가 거절된다(에뮬레이터 확인: error code 2) → 먼저 켠다.
                //    전원 쓰기가 끝난 뒤(성공·실패 모두) 풍향·속도를 보낸다.
                val area = fan.area
                val directWrites = {
                    if (a.toFace) sdk("HVAC.setHvacFanDirection(FACE)") { ok, fail -> hvac.setHvacFanDirection(area, FanDirectionType.FACE, { _, r -> ok(r?.name) }, fail) }
                    sdk("HVAC.setHvacFanSpeed($v)") { ok, fail -> hvac.setHvacFanSpeed(area, v, { _, r -> ok(r) }, fail) }
                }
                // 자동 공조(AUTO)가 켜져 있으면 수동 팬 쓰기가 거절될 수 있어 AUTO 를 끈 뒤 쓴다(깨운 뒤 되돌림).
                val fanWrites = {
                    sdk("HVAC.getHvacAutoState", onFail = { directWrites() }) { ok, fail ->
                        hvac.getHvacAutoState(area, { auto ->
                            ok(auto)
                            post {
                                if (auto == true) {
                                    if (prevHvacAuto == null) prevHvacAuto = true
                                    sdk("HVAC.setHvacAuto(false)", onFail = { directWrites() }) { ok2, fail2 ->
                                        hvac.setHvacAuto(area, false, { _, r -> ok2(r); post { directWrites() } }, fail2)
                                    }
                                } else directWrites()
                            }
                        }, fail)
                    }
                }
                val pArea = powerArea
                if (pArea == null) fanWrites()
                else sdk("HVAC.setHvacPower(${pArea.values.joinToString("+")}, true)", onFail = { fanWrites() }) { ok, fail ->
                    hvac.setHvacPower(pArea, true, { _, r -> ok(r); post { fanWrites() } }, fail)
                }
            }
            is WakeAction.SeatHeat -> {
                val v = scaleFromZero(seatHeat, a.fraction)
                sdk("Seat.setHvacSeatTemperature(ROW_1_LEFT, $v)") { ok, fail -> vehicle.getSeat().setHvacSeatTemperature(seatHeat.area, v, { _, r -> ok(r) }, fail) }
            }
            is WakeAction.SeatVent -> {
                val v = scaleFromZero(seatVent, a.fraction)
                sdk("Seat.setHvacSeatVentilation(ROW_1_LEFT, $v)") { ok, fail -> vehicle.getSeat().setHvacSeatVentilation(seatVent.area, v, { _, r -> ok(r) }, fail) }
            }
            is WakeAction.Tone -> Unit
        }
    }

    private fun beep(times: Int) {
        val t = tone ?: runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()?.also { tone = it }
        if (t == null) {
            log("ToneGenerator", false, "소리 장치를 열지 못함")
            return
        }
        repeat(times) { i -> main.postDelayed({ runCatching { t.startTone(ToneGenerator.TONE_PROP_BEEP2, 350) } }, i * 600L) }
        log("ToneGenerator(Android)", true, "삐 ${times}번")
    }

    // ------------------------------------------------------------------ 공통

    private fun scale(b: Bounds, f: Float): Int = (b.min + (b.max - b.min) * f.coerceIn(0f, 1f)).roundToInt().coerceIn(b.min, b.max)

    /** 열선·통풍: 음수(냉방 쪽)는 쓰지 않고 0..max 로 맞춘다. */
    private fun scaleFromZero(b: Bounds, f: Float): Int {
        val lo = maxOf(0, b.min)
        return (lo + (b.max - lo) * f.coerceIn(0f, 1f)).roundToInt().coerceIn(b.min, b.max)
    }

    private fun pickBounds(m: Map<SeatArea, AreaBounds<Int>>, preferDriver: Boolean = false): Bounds? {
        if (m.isEmpty()) return null
        val entry = (if (preferDriver) m.entries.firstOrNull { (a, _) -> SeatValue.ROW_1_LEFT in a.values } else null) ?: m.entries.first()
        return Bounds(entry.key, entry.value.minValue, entry.value.maxValue)
    }

    private fun Map<SeatArea, AreaBounds<Int>>.describeBounds() =
        entries.joinToString { (a, b) -> "${a.values.joinToString("+")}=${b.minValue}..${b.maxValue}" }

    private fun setWarning(name: String, on: Boolean) {
        if (on) publicWarnings += name else publicWarnings -= name
        pushWarnings()
    }

    private fun pushWarnings() = update { it.copy(activeWarnings = publicWarnings.toSet()) }

    private fun OilLevelState?.isLow() = this == OilLevelState.LOW || this == OilLevelState.CRITICALLY_LOW

    /**
     * 기어: Pleos Gear 도메인 deprecated → AAOS 표준 GEAR_SELECTION.
     * (SDK 2.3.2 의 Vehicle.getGear() 는 @Deprecated("use the Google GEAR_SELECTION property") 이고 공개 문서 19개 도메인에 없다.)
     * android.car CarPropertyManager 로 읽고 구독한다. 실패해도 앱은 계속 돌고, 시연 시나리오는 그대로 쓸 수 있다.
     */
    private fun subscribeGear() = io.post {
        val api = "AAOS GEAR_SELECTION"
        try {
            val c = Car.createCar(appContext) ?: error("Car.createCar 가 null")
            car = c
            val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val id = VehiclePropertyIds.GEAR_SELECTION
            val v = cpm.getIntProperty(id, 0)
            log("$api.getProperty", true, "${gearName(v)}($v)")
            update { it.copy(gear = gearState(v)) }
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>?) {
                    val g = value?.value as? Int ?: return
                    update { it.copy(gear = gearState(g)) }
                }
                override fun onErrorEvent(propId: Int, zone: Int) = log("$api.onErrorEvent", false, "zone=$zone")
            }
            if (!cpm.registerCallback(cb, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)) error("registerCallback false")
            post { unregisters += { cpm.unregisterCallback(cb) } }
            log("$api.registerCallback", true, "등록")
            post { onGearSupported(true) }
        } catch (e: Exception) {
            log(api, false, e.describe())
            post { onGearSupported(false) }
        }
    }

    private fun gearState(v: Int): GearState = when (v) {
        VehicleGear.GEAR_PARK -> GearState.PARK
        VehicleGear.GEAR_REVERSE -> GearState.REVERSE
        VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
        else -> GearState.DRIVE // DRIVE, GEAR_1..9
    }

    private fun gearName(v: Int): String = runCatching { VehicleGear.toString(v) }.getOrDefault(v.toString())

    /** 차 상태 갱신은 항상 메인 스레드에서. */
    private fun update(change: (VehicleSnapshot) -> VehicleSnapshot) = post {
        real = change(real)
        onSnapshot(real)
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun register(api: String, reg: () -> Unit, unreg: () -> Unit) = io.post {
        try {
            reg()
            post { unregisters += unreg }
            log(api, true, "등록")
        } catch (e: Exception) {
            log(api, false, e.describe())
        }
    }

    /**
     * SDK 호출 한 번. [block] 에 (성공 콜백, 실패 콜백)을 넘긴다.
     * 성공 콜백은 메인 스레드에서 로그를 남기고, 실패(예외 포함)는 [onFail] 로 알린다.
     */
    private fun sdk(
        api: String,
        onFail: (String) -> Unit = {},
        block: (ok: (Any?) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ) {
        val okCb: (Any?) -> Unit = { v -> post { log(api, true, v?.toString() ?: "null") } }
        val failCb: (Exception) -> Unit = { e -> post { log(api, false, e.describe()); onFail(e.describe()) } }
        io.post {
            try {
                block({ v -> okCb(v) }, failCb)
            } catch (e: Exception) {
                post { log(api, false, e.describe()); onFail(e.describe()) }
            }
        }
    }

    private fun log(api: String, ok: Boolean, detail: String) {
        val entry = SdkLogEntry(System.currentTimeMillis(), api, ok, detail)
        if (ok) Log.i(TAG, "$api OK $detail") else Log.w(TAG, "$api FAIL $detail")
        if (Looper.myLooper() == Looper.getMainLooper()) onLog(entry) else main.post { onLog(entry) }
    }

    private fun Exception.describe(): String = "${javaClass.simpleName}: ${message ?: "(메시지 없음)"}"

    companion object {
        const val TAG = "WakeTidy"
        const val PUB_ENGINE_OIL = PublicWarnings.ENGINE_OIL_LOW
        const val PUB_BRAKE_FLUID = PublicWarnings.BRAKE_FLUID_LOW
        const val PUB_WASHER = PublicWarnings.WASHER_FLUID_LOW
        const val PUB_POWER_DOWN = PublicWarnings.POWER_DOWN_WARNING
    }
}
