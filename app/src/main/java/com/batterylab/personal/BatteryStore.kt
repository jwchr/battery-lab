package com.batterylab.personal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Reading(
    val device: String,
    val timeMs: Long,
    val percent: Int,
    val charging: Boolean?,
    val source: String,
    val tempTenthsC: Int? = null,
    val voltageMv: Int? = null,
    val remainingUah: Int? = null
)

data class Experiment(
    val id: Long,
    val device: String,
    val mode: String,
    val startMs: Long,
    val startPct: Int,
    val endMs: Long?,
    val endPct: Int?
)

class BatteryStore(context: Context) : SQLiteOpenHelper(context, "battery_lab.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE readings (
            id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL,
            time_ms INTEGER NOT NULL, percent INTEGER NOT NULL, charging INTEGER,
            source TEXT NOT NULL, temp_tenths_c INTEGER, voltage_mv INTEGER,
            remaining_uah INTEGER)""")
        db.execSQL("CREATE INDEX idx_readings_device_time ON readings(device, time_ms)")
        db.execSQL("""CREATE TABLE experiments (
            id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL,
            mode TEXT NOT NULL, start_ms INTEGER NOT NULL, start_pct INTEGER NOT NULL,
            end_ms INTEGER, end_pct INTEGER)""")
        db.execSQL("""CREATE TABLE probe_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL,
            device TEXT NOT NULL, message TEXT NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun append(r: Reading) {
        require(r.percent in 0..100)
        val v = ContentValues().apply {
            put("device", r.device); put("time_ms", r.timeMs)
            put("percent", r.percent)
            if (r.charging != null) put("charging", if (r.charging) 1 else 0)
            else putNull("charging")
            put("source", r.source)
            if (r.tempTenthsC != null) put("temp_tenths_c", r.tempTenthsC)
            if (r.voltageMv != null) put("voltage_mv", r.voltageMv)
            if (r.remainingUah != null) put("remaining_uah", r.remainingUah)
        }
        writableDatabase.insertOrThrow("readings", null, v)
    }

    fun latest(device: String): Reading? = recent(device, 1).lastOrNull()

    fun recent(device: String, limit: Int = 500): List<Reading> {
        val output = mutableListOf<Reading>()
        readableDatabase.query("readings", null, "device=?", arrayOf(device), null, null,
            "time_ms DESC, id DESC", limit.coerceIn(1, 10000).toString()).use { c ->
            while (c.moveToNext()) {
                fun nullableInt(name: String): Int? {
                    val ix = c.getColumnIndexOrThrow(name)
                    return if (c.isNull(ix)) null else c.getInt(ix)
                }
                output.add(Reading(c.getString(c.getColumnIndexOrThrow("device")),
                    c.getLong(c.getColumnIndexOrThrow("time_ms")),
                    c.getInt(c.getColumnIndexOrThrow("percent")),
                    nullableInt("charging")?.let { it == 1 },
                    c.getString(c.getColumnIndexOrThrow("source")),
                    nullableInt("temp_tenths_c"), nullableInt("voltage_mv"),
                    nullableInt("remaining_uah")))
            }
        }
        return output.reversed()
    }

    fun deviceIds(): List<String> {
        val ids = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT DISTINCT device FROM readings ORDER BY device", null).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        return ids
    }

    fun startExperiment(device: String, mode: String, reading: Reading) {
        require(mode == "baseline" || mode == "monitoring")
        check(activeExperiment() == null) { "Finish the existing experiment first" }
        writableDatabase.insertOrThrow("experiments", null, ContentValues().apply {
            put("device", device); put("mode", mode)
            put("start_ms", reading.timeMs); put("start_pct", reading.percent)
        })
    }

    fun finishExperiment(reading: Reading) {
        val exp = activeExperiment() ?: error("No running experiment")
        check(exp.device == reading.device) { "Select the same Ring as when the test began" }
        writableDatabase.update("experiments", ContentValues().apply {
            put("end_ms", reading.timeMs); put("end_pct", reading.percent)
        }, "id=? AND end_ms IS NULL", arrayOf(exp.id.toString()))
    }

    fun activeExperiment(): Experiment? = experiments(200).firstOrNull { it.endMs == null }

    fun experiments(limit: Int = 100): List<Experiment> {
        val result = mutableListOf<Experiment>()
        readableDatabase.query("experiments", null, null, null, null, null,
            "start_ms DESC", limit.toString()).use { c ->
            while (c.moveToNext()) {
                fun nullableLong(name: String): Long? {
                    val i = c.getColumnIndexOrThrow(name)
                    return if (c.isNull(i)) null else c.getLong(i)
                }
                fun nullableInt(name: String): Int? {
                    val i = c.getColumnIndexOrThrow(name)
                    return if (c.isNull(i)) null else c.getInt(i)
                }
                result += Experiment(c.getLong(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("device")),
                    c.getString(c.getColumnIndexOrThrow("mode")),
                    c.getLong(c.getColumnIndexOrThrow("start_ms")),
                    c.getInt(c.getColumnIndexOrThrow("start_pct")),
                    nullableLong("end_ms"), nullableInt("end_pct"))
            }
        }
        return result
    }

    fun logProbe(device: String, message: String) {
        writableDatabase.insert("probe_events", null, ContentValues().apply {
            put("time_ms", System.currentTimeMillis())
            put("device", device)
            put("message", message.take(300))
        })
    }

    fun lastProbeEvent(device: String): String? {
        readableDatabase.query("probe_events", arrayOf("time_ms", "message"),
            "device=?", arrayOf(device), null, null, "time_ms DESC", "1").use { c ->
            return if (c.moveToFirst()) "${format(c.getLong(0))}: ${c.getString(1)}" else null
        }
    }

    fun exportCsv(out: Writer) {
        out.write("kind,device,mode_or_source,timestamp_utc,percentage,charging,temp_c,voltage_mv,remaining_uah,end_timestamp_utc,end_percentage,drop_pp_per_hour\n")
        readableDatabase.rawQuery("SELECT device,time_ms,percent,charging,source,temp_tenths_c,voltage_mv,remaining_uah FROM readings ORDER BY time_ms", null).use { c ->
            while (c.moveToNext()) {
                val charging = if (c.isNull(3)) "" else if (c.getInt(3) == 1) "true" else "false"
                val temp = if (c.isNull(5)) "" else (c.getInt(5) / 10.0).toString()
                val volt = if (c.isNull(6)) "" else c.getInt(6).toString()
                val charge = if (c.isNull(7)) "" else c.getInt(7).toString()
                out.write(listOf("reading", csv(c.getString(0)), csv(c.getString(4)),
                    utc(c.getLong(1)), c.getInt(2).toString(), charging, temp, volt, charge,
                    "", "", "").joinToString(",") + "\n")
            }
        }
        for (e in experiments(5000).reversed()) {
            val rate = BatteryMath.experimentDrain(e.startMs, e.endMs, e.startPct, e.endPct)
            out.write(listOf("experiment", csv(e.device), e.mode, utc(e.startMs),
                e.startPct.toString(), "", "", "", "", e.endMs?.let(::utc) ?: "",
                e.endPct?.toString() ?: "", rate?.toString() ?: "").joinToString(",") + "\n")
        }
    }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun utc(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(ms))

    companion object {
        fun format(ms: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))
    }
}

object PhoneBattery {
    fun read(context: Context): Reading? {
        val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val percent = (level.toDouble() / scale * 100).toInt().coerceIn(0, 100)
        val state = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val rawCharge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return Reading("phone", System.currentTimeMillis(), percent, charging, "android",
            intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1).takeIf { it >= 0 },
            intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            rawCharge.takeIf { it > 0 && it != Int.MAX_VALUE })
    }
}
