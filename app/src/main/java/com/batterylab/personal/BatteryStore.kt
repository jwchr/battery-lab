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
    val remainingUah: Int? = null,
    val detail: ReadingDetail? = null
)

/**
 * Audit fields recorded alongside a reading, kept raw. Null always means unknown or unsupported,
 * never zero. Currents are exactly what Android reports (nominally microamps; the sign
 * convention and real units vary by phone), the energy counter nominally nanowatt-hours.
 */
data class ReadingDetail(
    val currentNowRaw: Int? = null,
    val currentAvgRaw: Int? = null,
    /** BatteryManager.EXTRA_PLUGGED: 0 unplugged, 1 AC, 2 USB, 4 wireless, 8 dock. */
    val plugged: Int? = null,
    /** BatteryManager.EXTRA_STATUS: the full enum, not just charging yes/no. */
    val status: Int? = null,
    /** BatteryManager.EXTRA_HEALTH: a category, never a capacity number. */
    val health: Int? = null,
    val levelRaw: Int? = null,
    val scale: Int? = null,
    val cycleCount: Int? = null,
    val energyRaw: Long? = null,
    /** SystemClock.elapsedRealtime() at the read: immune to wall-clock changes within one boot. */
    val elapsedMs: Long? = null,
    val bootCount: Int? = null,
    /** What asked for the read: "job", "app", "manual". */
    val trigger: String? = null
) {
    companion object {
        /** Just the clock fields, for readings taken on this phone right now. */
        fun clock(context: Context, trigger: String?) =
            ReadingDetail(elapsedMs = android.os.SystemClock.elapsedRealtime(), bootCount = bootCount(context), trigger = trigger)

        fun bootCount(context: Context): Int? = try {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
        } catch (e: Exception) { null }
    }
}

data class Experiment(
    val id: Long,
    val device: String,
    val mode: String,
    val startMs: Long,
    val startPct: Int,
    val endMs: Long?,
    val endPct: Int?
)

/**
 * The history database. One shared instance per process (see [get]) so the screens, the poll job
 * and the watch receiver never hold competing connections; write-ahead logging lets reads and
 * writes overlap.
 */
class BatteryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "battery_lab.db", null, 3) {
    init { setWriteAheadLoggingEnabled(true) }

    /** Shared for the life of the process; callers may still call close(), which does nothing. */
    override fun close() = Unit

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE readings (
            id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL,
            time_ms INTEGER NOT NULL, percent INTEGER NOT NULL, charging INTEGER,
            source TEXT NOT NULL, temp_tenths_c INTEGER, voltage_mv INTEGER,
            remaining_uah INTEGER)""")
        addDetailColumns(db)
        db.execSQL("CREATE INDEX idx_readings_device_time ON readings(device, time_ms)")
        db.execSQL("""CREATE TABLE experiments (
            id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL,
            mode TEXT NOT NULL, start_ms INTEGER NOT NULL, start_pct INTEGER NOT NULL,
            end_ms INTEGER, end_pct INTEGER)""")
        db.execSQL("""CREATE TABLE probe_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL,
            device TEXT NOT NULL, message TEXT NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: one stable Ring history. Readings taken over Bluetooth used "ring:<address>",
            // which split the Ring's timeline and put its address in backups.
            for (table in listOf("readings", "experiments", "probe_events")) {
                db.execSQL("UPDATE $table SET device=? WHERE device LIKE 'ring:%' AND device<>?",
                    arrayOf(RING_ID, RING_ID))
            }
        }
        // v3: audit columns, added empty. Existing rows keep every value and read as unknown.
        if (oldVersion < 3) addDetailColumns(db)
    }

    private fun addDetailColumns(db: SQLiteDatabase) {
        for ((name, type) in DETAIL_COLUMNS) db.execSQL("ALTER TABLE readings ADD COLUMN $name $type")
    }

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
            r.detail?.let { d ->
                d.currentNowRaw?.let { put("current_now_raw", it) }
                d.currentAvgRaw?.let { put("current_avg_raw", it) }
                d.plugged?.let { put("plugged", it) }
                d.status?.let { put("status", it) }
                d.health?.let { put("health", it) }
                d.levelRaw?.let { put("level_raw", it) }
                d.scale?.let { put("scale", it) }
                d.cycleCount?.let { put("cycle_count", it) }
                d.energyRaw?.let { put("energy_raw", it) }
                d.elapsedMs?.let { put("elapsed_ms", it) }
                d.bootCount?.let { put("boot_count", it) }
                d.trigger?.let { put("read_trigger", it) }
            }
        }
        writableDatabase.insertOrThrow("readings", null, v)
    }

    fun latest(device: String): Reading? = recent(device, 1).lastOrNull()

    fun recent(device: String, limit: Int = 500): List<Reading> =
        query("device=?", arrayOf(device), limit.coerceIn(1, 10000).toString())

    /** Every reading for [device] since [fromMs], oldest first; for long-range history. */
    fun since(device: String, fromMs: Long): List<Reading> =
        query("device=? AND time_ms>=?", arrayOf(device, fromMs.toString()), null)

    private fun query(where: String, args: Array<String>, limit: String?): List<Reading> {
        val output = mutableListOf<Reading>()
        readableDatabase.query("readings", null, where, args, null, null,
            "time_ms DESC, id DESC", limit).use { c ->
            while (c.moveToNext()) {
                fun nullableInt(name: String): Int? {
                    val ix = c.getColumnIndexOrThrow(name)
                    return if (c.isNull(ix)) null else c.getInt(ix)
                }
                fun nullableLong(name: String): Long? {
                    val ix = c.getColumnIndexOrThrow(name)
                    return if (c.isNull(ix)) null else c.getLong(ix)
                }
                val trigIx = c.getColumnIndexOrThrow("read_trigger")
                val detail = ReadingDetail(nullableInt("current_now_raw"), nullableInt("current_avg_raw"),
                    nullableInt("plugged"), nullableInt("status"), nullableInt("health"),
                    nullableInt("level_raw"), nullableInt("scale"), nullableInt("cycle_count"),
                    nullableLong("energy_raw"), nullableLong("elapsed_ms"), nullableInt("boot_count"),
                    if (c.isNull(trigIx)) null else c.getString(trigIx)).takeIf { it != ReadingDetail() }
                output.add(Reading(c.getString(c.getColumnIndexOrThrow("device")),
                    c.getLong(c.getColumnIndexOrThrow("time_ms")),
                    c.getInt(c.getColumnIndexOrThrow("percent")),
                    nullableInt("charging")?.let { it == 1 },
                    c.getString(c.getColumnIndexOrThrow("source")),
                    nullableInt("temp_tenths_c"), nullableInt("voltage_mv"),
                    nullableInt("remaining_uah"), detail))
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
        val id = writableDatabase.insert("probe_events", null, ContentValues().apply {
            put("time_ms", System.currentTimeMillis())
            put("device", device)
            put("message", message.take(300))
        })
        // Diagnostics only: keep the newest few thousand rows (readings are never trimmed).
        if (id > 0 && id % 100 == 0L) writableDatabase.delete("probe_events", "id<=?", arrayOf((id - PROBE_KEEP).toString()))
    }

    fun lastProbeEvent(device: String): String? {
        readableDatabase.query("probe_events", arrayOf("time_ms", "message"),
            "device=?", arrayOf(device), null, null, "time_ms DESC", "1").use { c ->
            return if (c.moveToFirst()) "${format(c.getLong(0))}: ${c.getString(1)}" else null
        }
    }

    fun exportCsv(out: Writer) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun utc(ms: Long) = iso.format(Date(ms))
        out.write("kind,device,mode_or_source,timestamp_utc,percentage,charging,temp_c,voltage_mv,remaining_uah,end_timestamp_utc,end_percentage,drop_pp_per_hour," +
            DETAIL_COLUMNS.joinToString(",") { it.first } + "\n")
        // Audit columns go last so tools that read the original columns by position still work.
        val blanks = List(DETAIL_COLUMNS.size) { "" }
        readableDatabase.rawQuery("SELECT device,time_ms,percent,charging,source,temp_tenths_c,voltage_mv,remaining_uah," +
            DETAIL_COLUMNS.joinToString(",") { it.first } + " FROM readings ORDER BY time_ms", null).use { c ->
            while (c.moveToNext()) {
                val charging = if (c.isNull(3)) "" else if (c.getInt(3) == 1) "true" else "false"
                val temp = if (c.isNull(5)) "" else (c.getInt(5) / 10.0).toString()
                val volt = if (c.isNull(6)) "" else c.getInt(6).toString()
                val charge = if (c.isNull(7)) "" else c.getInt(7).toString()
                out.write(listOf("reading", csv(c.getString(0)), csv(c.getString(4)),
                    utc(c.getLong(1)), c.getInt(2).toString(), charging, temp, volt, charge,
                    "", "", "").plus(DETAIL_COLUMNS.indices.map { i ->
                        val ix = 8 + i
                        when {
                            c.isNull(ix) -> ""
                            DETAIL_COLUMNS[i].second == "TEXT" -> csv(c.getString(ix))
                            else -> c.getLong(ix).toString()
                        }
                    }).joinToString(",") + "\n")
            }
        }
        for (e in experiments(5000).reversed()) {
            val rate = BatteryMath.experimentDrain(e.startMs, e.endMs, e.startPct, e.endPct)
            out.write(listOf("experiment", csv(e.device), e.mode, utc(e.startMs),
                e.startPct.toString(), "", "", "", "", e.endMs?.let(::utc) ?: "",
                e.endPct?.toString() ?: "", rate?.toString() ?: "").plus(blanks).joinToString(",") + "\n")
            Unit
        }
    }

    /** Quoted CSV field; a leading = + - @ or control character is defused for spreadsheets. */
    private fun csv(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    companion object {
        /** Every Ring reading, from the widget or Bluetooth, shares this history id. */
        const val RING_ID = "ring:samsung_widget"
        private const val PROBE_KEEP = 3000L
        /** Audit columns added in schema v3, in export order. */
        private val DETAIL_COLUMNS = listOf(
            "current_now_raw" to "INTEGER", "current_avg_raw" to "INTEGER", "plugged" to "INTEGER",
            "status" to "INTEGER", "health" to "INTEGER", "level_raw" to "INTEGER", "scale" to "INTEGER",
            "cycle_count" to "INTEGER", "energy_raw" to "INTEGER", "elapsed_ms" to "INTEGER",
            "boot_count" to "INTEGER", "read_trigger" to "TEXT")
        @Volatile private var shared: BatteryStore? = null
        fun get(context: Context): BatteryStore =
            shared ?: synchronized(this) { shared ?: BatteryStore(context).also { shared = it } }

        fun format(ms: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))
    }
}

object PhoneBattery {
    /** The phone's own battery right now. [trigger] says what asked: "job", "app" or "manual". */
    fun read(context: Context, trigger: String): Reading? {
        val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val percent = (level.toDouble() / scale * 100).toInt().coerceIn(0, 100)
        val state = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val rawCharge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        // Int.MIN_VALUE / Long.MIN_VALUE mean "not supported"; a real 0 is kept as 0.
        fun prop(id: Int) = bm.getIntProperty(id).takeIf { it != Int.MIN_VALUE }
        fun extra(name: String) = intent.getIntExtra(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val detail = ReadingDetail.clock(context, trigger).copy(
            currentNowRaw = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAvgRaw = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            plugged = extra(BatteryManager.EXTRA_PLUGGED),
            status = state.takeIf { it >= 0 },
            health = extra(BatteryManager.EXTRA_HEALTH),
            levelRaw = level, scale = scale,
            cycleCount = extra("android.os.extra.CYCLE_COUNT")?.takeIf { it >= 0 },
            energyRaw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).takeIf { it != Long.MIN_VALUE })
        return Reading("phone", System.currentTimeMillis(), percent, charging, "android",
            intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1).takeIf { it >= 0 },
            intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            rawCharge.takeIf { it > 0 && it != Int.MAX_VALUE }, detail)
    }
}
