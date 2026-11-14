package com.moyeon.waketidy.data

import android.content.Context
import com.moyeon.waketidy.engine.Driver
import com.moyeon.waketidy.engine.HandoverCard
import com.moyeon.waketidy.engine.LogbookCsv
import com.moyeon.waketidy.engine.LogbookEntry
import com.moyeon.waketidy.engine.Purpose
import com.moyeon.waketidy.engine.WakeMethod
import com.moyeon.waketidy.engine.WakeRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 앱 안(로컬)에만 저장한다. 서버·클라우드로 보내지 않는다.
 * 깨우기 기록, 깨우는 방식 프로필, 운행기록부, 다음 사람 카드.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("wake_and_tidy", Context.MODE_PRIVATE)
    private val exportDir: File = context.getExternalFilesDir(null) ?: context.filesDir

    val drivers = listOf(
        Driver("영업1팀", "사원 A"),
        Driver("영업1팀", "사원 B"),
        Driver("총무팀", "사원 C"),
    )

    var methods: Set<WakeMethod>
        get() = prefs.getString("methods", null)
            ?.split(',')?.mapNotNull { n -> WakeMethod.entries.firstOrNull { it.name == n } }?.toSet()
            ?: setOf(WakeMethod.AMBIENT, WakeMethod.FAN, WakeMethod.SEAT_HEAT)
        set(v) = prefs.edit().putString("methods", v.joinToString(",") { it.name }).apply()

    var limitMinutes: Int
        get() = prefs.getInt("limit", 100)
        set(v) = prefs.edit().putInt("limit", v).apply()

    var driverIndex: Int
        get() = prefs.getInt("driver", 1).coerceIn(0, drivers.lastIndex)
        set(v) = prefs.edit().putInt("driver", v).apply()

    // ---- 깨우기 기록 ----
    fun wakeRecords(): List<WakeRecord> = array("wakes").map { o ->
        WakeRecord(
            atMs = o.getLong("at"),
            restMinutes = o.getInt("rest"),
            methods = o.getString("methods").split(',').mapNotNull { n -> WakeMethod.entries.firstOrNull { it.name == n } }.toSet(),
            levelReached = o.getInt("level"),
            secondsToWake = o.getLong("sec"),
        )
    }

    fun addWakeRecord(r: WakeRecord) = append("wakes", JSONObject().apply {
        put("at", r.atMs); put("rest", r.restMinutes); put("methods", r.methods.joinToString(",") { it.name })
        put("level", r.levelReached); put("sec", r.secondsToWake)
    }, keepLast = 50)

    // ---- 운행기록부 ----
    fun logbook(): List<LogbookEntry> = array("logbook").map { o ->
        LogbookEntry(
            date = LocalDate.parse(o.getString("date")),
            driver = Driver(o.getString("dept"), o.getString("name")),
            startKm = o.getDouble("start"),
            endKm = o.getDouble("end"),
            purpose = Purpose.valueOf(o.getString("purpose")),
            memo = o.optString("memo", ""),
            demo = o.optBoolean("demo", false),
        )
    }

    fun addLogbook(e: LogbookEntry) = append("logbook", JSONObject().apply {
        put("date", e.date.toString()); put("dept", e.driver.dept); put("name", e.driver.name)
        put("start", e.startKm); put("end", e.endKm); put("purpose", e.purpose.name); put("memo", e.memo); put("demo", e.demo)
    }, keepLast = 1000)

    fun clearDemoLogbook() {
        val kept = JSONArray()
        array("logbook").filter { !it.optBoolean("demo", false) }.forEach { kept.put(it) }
        prefs.edit().putString("logbook", kept.toString()).apply()
    }

    /** 운행기록부 CSV 를 앱 저장소에 쓰고 파일을 돌려준다. */
    fun exportCsv(entries: List<LogbookEntry>, today: LocalDate): File {
        val f = File(exportDir, "waketidy_logbook_${today.format(DateTimeFormatter.ofPattern("yyyy-MM"))}.csv")
        f.writeText(LogbookCsv.build(entries, vehicleName = "법인 공용차", plate = ""), Charsets.UTF_8)
        return f
    }

    // ---- 다음 사람 카드 ----
    fun handover(): HandoverCard? = prefs.getString("handover", null)?.let { s ->
        runCatching {
            val o = JSONObject(s)
            HandoverCard(
                createdAtMs = o.getLong("at"),
                previousDriver = o.optString("driver").ifBlank { null },
                tripKm = if (o.has("km")) o.getDouble("km") else null,
                odometerKm = if (o.has("odo")) o.getDouble("odo") else null,
                onThenOff = o.getJSONArray("onOff").strings(),
                stillOn = o.getJSONArray("stillOn").strings(),
                fuelPercent = if (o.has("fuel")) o.getInt("fuel") else null,
                evSocPercent = if (o.has("soc")) o.getInt("soc") else null,
                demo = o.optBoolean("demo", false),
            )
        }.getOrNull()
    }

    fun saveHandover(c: HandoverCard) {
        val o = JSONObject().apply {
            put("at", c.createdAtMs); put("driver", c.previousDriver ?: "")
            c.tripKm?.let { put("km", it) }
            c.odometerKm?.let { put("odo", it) }
            put("onOff", JSONArray(c.onThenOff)); put("stillOn", JSONArray(c.stillOn))
            c.fuelPercent?.let { put("fuel", it) }
            c.evSocPercent?.let { put("soc", it) }
            put("demo", c.demo)
        }
        prefs.edit().putString("handover", o.toString()).apply()
    }

    private fun array(key: String): List<JSONObject> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun append(key: String, o: JSONObject, keepLast: Int) {
        val all = (array(key) + o).takeLast(keepLast)
        prefs.edit().putString(key, JSONArray().apply { all.forEach { put(it) } }.toString()).apply()
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
