package com.batterylab.personal

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Reader
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
 * convention and real units vary by phone and are worked out by [CapabilityProbe]), the energy
 * counter nominally nanowatt-hours.
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
    /** What asked for the read: "job", "app", "manual", "plug", "unplug", "plug_settled". */
    val trigger: String? = null,
    /** Android's own estimates at this moment, recorded so they can be scored against what happened. */
    val androidDischargeMs: Long? = null,
    val androidChargeMs: Long? = null,
    /** Whether Android says its time-left estimate is learned from this phone's usage. */
    val androidPersonalized: Boolean? = null,
    /** PowerManager thermal status: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
    val thermalStatus: Int? = null,
    // ---- v6: context at the moment of the read ----
    /** "wifi", "cellular", "other" or "none", with "+vpn" when a VPN is up. */
    val transport: String? = null,
    /** Display.STATE_*: 1 off, 2 on, 3 doze (Always-On Display), 4 doze-suspend. */
    val displayState: Int? = null,
    val powerSave: Boolean? = null,
    val deviceIdle: Boolean? = null,
    /** PowerManager.getThermalHeadroom(10 s) × 10; 10 means the throttling threshold is reached. */
    val thermalHeadroomTenths: Int? = null,
    /** What the charger advertised, from the battery broadcast; 0 or null when unplugged. */
    val maxChargingUa: Int? = null,
    val maxChargingUv: Int? = null,
    /** [QualityFlags] bitmask; null means not assessed (rows recorded before v6). */
    val qualityFlags: Int? = null,
    /** Ring Bluetooth signal strength during a direct read. */
    val rssiDbm: Int? = null,
    /** Anything else worth keeping raw: widget arc progress, Ring packet bytes. */
    val rawExtra: String? = null
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

/** A note about something that changed: an update, a new charger, a setting. */
data class JournalEntry(
    val id: Long,
    val timeMs: Long,
    /** For detected updates: the last time the old version was seen, so the change is a window. */
    val windowStartMs: Long?,
    val device: String,
    val category: String,
    val title: String,
    val detailsJson: String?
)

/** What one field on one device can do, as last checked. */
data class CapabilityRow(
    val fieldKey: String,
    val deviceKind: String,
    val firmwareBuild: String,
    val status: String,
    val sourceApi: String,
    val units: String,
    val lastValueSample: String?,
    val observedMinStep: String?,
    val notes: String?,
    val checkedTimeMs: Long
)

/** A forecast frozen when it was made, scored later against what actually happened. */
data class Forecast(
    val id: Long,
    val device: String,
    val issuedMs: Long,
    val startPct: Int,
    val labRatePerHour: Double,
    val labHoursToEmpty: Double,
    val androidHoursToEmpty: Double?,
    val androidPersonalized: Boolean?,
    val modelVersion: String,
    val actual2h: Double?,
    val actual4h: Double?,
    val actual8h: Double?,
    val status: String
) {
    fun labPredicted(hours: Double) = startPct - labRatePerHour * hours
    /** Android only gives time to empty; as a straight line from [startPct] it implies this. */
    fun androidPredicted(hours: Double) = androidHoursToEmpty?.takeIf { it > 0 }?.let { startPct - startPct / it * hours }
}

/**
 * The history database. One shared instance per process (see [get]) so the screens, the poll job
 * and the watch receiver never hold competing connections; write-ahead logging lets reads and
 * writes overlap.
 */
class BatteryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "battery_lab.db", null, 7) {
    init { setWriteAheadLoggingEnabled(true) }

    /** Shared for the life of the process; callers may still call close(), which does nothing. */
    override fun close() = Unit

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE readings (
            id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL,
            time_ms INTEGER NOT NULL, percent INTEGER NOT NULL, charging INTEGER,
            source TEXT NOT NULL, temp_tenths_c INTEGER, voltage_mv INTEGER,
            remaining_uah INTEGER)""")
        addColumns(db, DETAIL_COLUMNS)
        db.execSQL("CREATE INDEX idx_readings_device_time ON readings(device, time_ms)")
        db.execSQL("""CREATE TABLE experiments (
            id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL,
            mode TEXT NOT NULL, start_ms INTEGER NOT NULL, start_pct INTEGER NOT NULL,
            end_ms INTEGER, end_pct INTEGER)""")
        db.execSQL("""CREATE TABLE probe_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL,
            device TEXT NOT NULL, message TEXT NOT NULL)""")
        createV6Tables(db)
        createV7Indexes(db)
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
        if (oldVersion < 3) addColumns(db, V3_COLUMNS)
        // v4: Android's own time-left / time-to-full estimates.
        if (oldVersion < 4) addColumns(db, V4_COLUMNS)
        // v5: Android's thermal status (is the phone being throttled for heat).
        if (oldVersion < 5) addColumns(db, V5_COLUMNS)
        // v6: context per read (network, screen, saver, Doze, headroom, charger limits), data
        // quality flags, Ring signal and raw extras; plus the journal, capability and forecast tables.
        if (oldVersion < 6) {
            addColumns(db, V6_COLUMNS)
            createV6Tables(db)
        }
        if (oldVersion < 7) createV7Indexes(db)
    }

    /**
     * v7: indexes only. Each save looks up the device's last reading from the same source, which
     * walked the device's whole history for a rarely used source; and every check scans pending
     * forecasts by status.
     */
    private fun createV7Indexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_readings_device_source_time ON readings(device, source, time_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_forecasts_device_status ON forecasts(device, status)")
    }

    private fun addColumns(db: SQLiteDatabase, columns: List<Pair<String, String>>) {
        for ((name, type) in columns) db.execSQL("ALTER TABLE readings ADD COLUMN $name $type")
    }

    private fun createV6Tables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS capability_probes (
            field_key TEXT NOT NULL, device_kind TEXT NOT NULL, firmware_build TEXT NOT NULL,
            status TEXT NOT NULL, source_api TEXT NOT NULL, units TEXT NOT NULL,
            last_value_sample TEXT, observed_min_step TEXT, notes TEXT,
            checked_time_ms INTEGER NOT NULL, PRIMARY KEY (field_key, device_kind))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS context_journal (
            id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL,
            window_start_ms INTEGER, device TEXT NOT NULL, category TEXT NOT NULL,
            title TEXT NOT NULL, details_json TEXT)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS forecasts (
            id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL,
            issued_ms INTEGER NOT NULL, start_pct INTEGER NOT NULL,
            lab_rate_pp_per_hr REAL NOT NULL, lab_hours_to_empty REAL NOT NULL,
            android_hours_to_empty REAL, android_personalized INTEGER,
            model_version TEXT NOT NULL, actual_2h_pct REAL, actual_4h_pct REAL,
            actual_8h_pct REAL, status TEXT NOT NULL DEFAULT 'PENDING')""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_forecasts_device_issued ON forecasts(device, issued_ms)")
    }

    // ---- readings ------------------------------------------------------------------------

    /**
     * Saves a reading. Quality flags are assessed against the previous reading of the same device
     * and source, keeping any flags the caller already set.
     */
    fun append(r: Reading) {
        require(r.percent in 0..100)
        val flags = QualityFlags.assess(latestFrom(r.device, r.source), r)
        val withFlags = r.copy(detail = (r.detail ?: ReadingDetail()).copy(qualityFlags = flags))
        writableDatabase.insertOrThrow("readings", null, values(withFlags))
    }

    private fun values(r: Reading) = ContentValues().apply {
        put("device", r.device); put("time_ms", r.timeMs)
        put("percent", r.percent)
        if (r.charging != null) put("charging", if (r.charging) 1 else 0) else putNull("charging")
        put("source", r.source)
        r.tempTenthsC?.let { put("temp_tenths_c", it) }
        r.voltageMv?.let { put("voltage_mv", it) }
        r.remainingUah?.let { put("remaining_uah", it) }
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
            d.androidDischargeMs?.let { put("android_discharge_ms", it) }
            d.androidChargeMs?.let { put("android_charge_ms", it) }
            d.androidPersonalized?.let { put("android_personalized", if (it) 1 else 0) }
            d.thermalStatus?.let { put("thermal_status", it) }
            d.transport?.let { put("transport", it) }
            d.displayState?.let { put("display_state", it) }
            d.powerSave?.let { put("power_save", if (it) 1 else 0) }
            d.deviceIdle?.let { put("device_idle", if (it) 1 else 0) }
            d.thermalHeadroomTenths?.let { put("thermal_headroom_tenths", it) }
            d.maxChargingUa?.let { put("max_charging_ua", it) }
            d.maxChargingUv?.let { put("max_charging_uv", it) }
            d.qualityFlags?.let { put("quality_flags", it) }
            d.rssiDbm?.let { put("rssi_dbm", it) }
            d.rawExtra?.let { put("raw_extra", it) }
        }
    }

    fun latest(device: String): Reading? = recent(device, 1).lastOrNull()

    private fun latestFrom(device: String, source: String): Reading? =
        query("device=? AND source=?", arrayOf(device, source), "1").lastOrNull()

    fun recent(device: String, limit: Int = 500): List<Reading> =
        query("device=?", arrayOf(device), limit.coerceIn(1, 10000).toString())

    /** Every reading for [device] since [fromMs], oldest first; for long-range history. */
    fun since(device: String, fromMs: Long): List<Reading> =
        query("device=? AND time_ms>=?", arrayOf(device, fromMs.toString()), null)

    /**
     * The watch's history from every path it arrives by (Samsung's widget and, once installed,
     * Battery Lab's watch app), merged in time order. A widget reading within 5 minutes of a
     * watch-app reading is dropped, since the watch app measured it directly. Each source's own
     * rows stay separate in the database for the widget-lag scorecard.
     */
    /**
     * The one Watch history the screens and forecasts use: Samsung's widget when it has ever
     * reported, else the watch app. Picking whichever id wrote last made the Watch card and its
     * stats flip between two separate histories from one check to the next.
     */
    fun primaryWatchId(): String? {
        val ids = deviceIds().filter { it.startsWith("watch:") }
        return if (SamsungWidgetSource.WATCH_DEVICE in ids) SamsungWidgetSource.WATCH_DEVICE else ids.firstOrNull()
    }

    /** Watch-app readings kept beside the widget's, for the audit's widget-vs-watch comparison. */
    fun companionWatchReadings(fromMs: Long): List<Reading> =
        deviceIds().filter { it.startsWith("watch:") && it != SamsungWidgetSource.WATCH_DEVICE }.flatMap { since(it, fromMs) }

    fun has(device: String, source: String, timeMs: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM readings WHERE device=? AND source=? AND time_ms=? LIMIT 1",
            arrayOf(device, source, timeMs.toString())).use { it.moveToFirst() }

    fun mergedWatchReadings(fromMs: Long): List<Reading> {
        val ids = deviceIds().filter { it.startsWith("watch:") }
        return mergeWatch(ids.flatMap { since(it, fromMs) })
    }

    private fun query(where: String, args: Array<String>, limit: String?): List<Reading> {
        val output = mutableListOf<Reading>()
        readableDatabase.query("readings", null, where, args, null, null,
            "time_ms DESC, id DESC", limit).use { c ->
            while (c.moveToNext()) output.add(readingAt(c))
        }
        return output.reversed()
    }

    private fun readingAt(c: Cursor): Reading {
        fun int(name: String): Int? { val i = c.getColumnIndexOrThrow(name); return if (c.isNull(i)) null else c.getInt(i) }
        fun long(name: String): Long? { val i = c.getColumnIndexOrThrow(name); return if (c.isNull(i)) null else c.getLong(i) }
        fun str(name: String): String? { val i = c.getColumnIndexOrThrow(name); return if (c.isNull(i)) null else c.getString(i) }
        val detail = ReadingDetail(
            currentNowRaw = int("current_now_raw"), currentAvgRaw = int("current_avg_raw"),
            plugged = int("plugged"), status = int("status"), health = int("health"),
            levelRaw = int("level_raw"), scale = int("scale"), cycleCount = int("cycle_count"),
            energyRaw = long("energy_raw"), elapsedMs = long("elapsed_ms"), bootCount = int("boot_count"),
            trigger = str("read_trigger"),
            androidDischargeMs = long("android_discharge_ms"), androidChargeMs = long("android_charge_ms"),
            androidPersonalized = int("android_personalized")?.let { it == 1 },
            thermalStatus = int("thermal_status"),
            transport = str("transport"), displayState = int("display_state"),
            powerSave = int("power_save")?.let { it == 1 }, deviceIdle = int("device_idle")?.let { it == 1 },
            thermalHeadroomTenths = int("thermal_headroom_tenths"),
            maxChargingUa = int("max_charging_ua"), maxChargingUv = int("max_charging_uv"),
            qualityFlags = int("quality_flags"), rssiDbm = int("rssi_dbm"), rawExtra = str("raw_extra")
        ).takeIf { it != ReadingDetail() }
        return Reading(c.getString(c.getColumnIndexOrThrow("device")),
            c.getLong(c.getColumnIndexOrThrow("time_ms")),
            c.getInt(c.getColumnIndexOrThrow("percent")),
            // Plugged in counts as on the charger, for old readings too (see PhoneBattery.read).
            int("charging")?.let { it == 1 || (detail?.plugged ?: 0) > 0 },
            c.getString(c.getColumnIndexOrThrow("source")),
            int("temp_tenths_c"), int("voltage_mv"), int("remaining_uah"), detail)
    }

    fun deviceIds(): List<String> {
        val ids = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT DISTINCT device FROM readings ORDER BY device", null).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        return ids
    }

    fun readingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    // ---- experiments ---------------------------------------------------------------------

    fun startExperiment(device: String, mode: String, reading: Reading) {
        require(mode in EXPERIMENT_MODES)
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

    /** Ends the running test without a result (for example when the final read failed). */
    fun abandonExperiment(reason: String) {
        val exp = activeExperiment() ?: return
        writableDatabase.delete("experiments", "id=? AND end_ms IS NULL", arrayOf(exp.id.toString()))
        logProbe("experiment", "Test abandoned: $reason")
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

    // ---- diagnostics ---------------------------------------------------------------------

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

    /** Probe events for [device] since [fromMs], oldest first, as (time, message). */
    fun probeEvents(device: String, fromMs: Long): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        readableDatabase.query("probe_events", arrayOf("time_ms", "message"), "device=? AND time_ms>=?",
            arrayOf(device, fromMs.toString()), null, null, "time_ms ASC", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to c.getString(1)
        }
        return out
    }

    // ---- context journal -----------------------------------------------------------------

    fun addJournal(device: String, category: String, title: String, timeMs: Long = System.currentTimeMillis(),
                   windowStartMs: Long? = null, detailsJson: String? = null) {
        writableDatabase.insertOrThrow("context_journal", null, ContentValues().apply {
            put("time_ms", timeMs); windowStartMs?.let { put("window_start_ms", it) }
            put("device", device); put("category", category); put("title", title.take(200))
            detailsJson?.let { put("details_json", it.take(2000)) }
        })
    }

    fun journal(limit: Int = 500): List<JournalEntry> {
        val out = mutableListOf<JournalEntry>()
        readableDatabase.query("context_journal", null, null, null, null, null, "time_ms DESC", limit.toString()).use { c ->
            while (c.moveToNext()) {
                val ws = c.getColumnIndexOrThrow("window_start_ms")
                val dj = c.getColumnIndexOrThrow("details_json")
                out += JournalEntry(c.getLong(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("time_ms")),
                    if (c.isNull(ws)) null else c.getLong(ws), c.getString(c.getColumnIndexOrThrow("device")),
                    c.getString(c.getColumnIndexOrThrow("category")), c.getString(c.getColumnIndexOrThrow("title")),
                    if (c.isNull(dj)) null else c.getString(dj))
            }
        }
        return out
    }

    fun deleteJournal(id: Long) {
        writableDatabase.delete("context_journal", "id=?", arrayOf(id.toString()))
    }

    // ---- capability probes ---------------------------------------------------------------

    fun saveCapabilities(rows: List<CapabilityRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) db.insertWithOnConflict("capability_probes", null, ContentValues().apply {
                put("field_key", r.fieldKey); put("device_kind", r.deviceKind); put("firmware_build", r.firmwareBuild)
                put("status", r.status); put("source_api", r.sourceApi); put("units", r.units)
                put("last_value_sample", r.lastValueSample); put("observed_min_step", r.observedMinStep)
                put("notes", r.notes); put("checked_time_ms", r.checkedTimeMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun capabilities(): List<CapabilityRow> {
        val out = mutableListOf<CapabilityRow>()
        readableDatabase.query("capability_probes", null, null, null, null, null, "device_kind, field_key", null).use { c ->
            fun s(n: String): String? { val i = c.getColumnIndexOrThrow(n); return if (c.isNull(i)) null else c.getString(i) }
            while (c.moveToNext()) out += CapabilityRow(s("field_key")!!, s("device_kind")!!, s("firmware_build")!!,
                s("status")!!, s("source_api")!!, s("units")!!, s("last_value_sample"), s("observed_min_step"),
                s("notes"), c.getLong(c.getColumnIndexOrThrow("checked_time_ms")))
        }
        return out
    }

    // ---- forecasts -----------------------------------------------------------------------

    fun addForecast(f: Forecast) {
        writableDatabase.insertOrThrow("forecasts", null, ContentValues().apply {
            put("device", f.device); put("issued_ms", f.issuedMs); put("start_pct", f.startPct)
            put("lab_rate_pp_per_hr", f.labRatePerHour); put("lab_hours_to_empty", f.labHoursToEmpty)
            f.androidHoursToEmpty?.let { put("android_hours_to_empty", it) }
            f.androidPersonalized?.let { put("android_personalized", if (it) 1 else 0) }
            put("model_version", f.modelVersion); put("status", f.status)
        })
    }

    fun forecasts(device: String? = null, status: String? = null): List<Forecast> {
        val where = listOfNotNull(device?.let { "device=?" }, status?.let { "status=?" }).joinToString(" AND ").ifEmpty { null }
        val args = listOfNotNull(device, status).toTypedArray().ifEmpty { null }
        val out = mutableListOf<Forecast>()
        readableDatabase.query("forecasts", null, where, args, null, null, "issued_ms ASC", null).use { c ->
            fun d(n: String): Double? { val i = c.getColumnIndexOrThrow(n); return if (c.isNull(i)) null else c.getDouble(i) }
            while (c.moveToNext()) {
                val p = c.getColumnIndexOrThrow("android_personalized")
                out += Forecast(c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("device")),
                    c.getLong(c.getColumnIndexOrThrow("issued_ms")), c.getInt(c.getColumnIndexOrThrow("start_pct")),
                    d("lab_rate_pp_per_hr")!!, d("lab_hours_to_empty")!!, d("android_hours_to_empty"),
                    if (c.isNull(p)) null else c.getInt(p) == 1, c.getString(c.getColumnIndexOrThrow("model_version")),
                    d("actual_2h_pct"), d("actual_4h_pct"), d("actual_8h_pct"), c.getString(c.getColumnIndexOrThrow("status")))
            }
        }
        return out
    }

    fun lastForecastMs(device: String): Long? = readableDatabase.rawQuery(
        "SELECT MAX(issued_ms) FROM forecasts WHERE device=?", arrayOf(device)).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }

    fun updateForecast(id: Long, actual2h: Double?, actual4h: Double?, actual8h: Double?, status: String) {
        writableDatabase.update("forecasts", ContentValues().apply {
            if (actual2h != null) put("actual_2h_pct", actual2h) else putNull("actual_2h_pct")
            if (actual4h != null) put("actual_4h_pct", actual4h) else putNull("actual_4h_pct")
            if (actual8h != null) put("actual_8h_pct", actual8h) else putNull("actual_8h_pct")
            put("status", status)
        }, "id=?", arrayOf(id.toString()))
    }

    // ---- export / restore ----------------------------------------------------------------

    fun exportCsv(out: Writer) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun utc(ms: Long) = iso.format(Date(ms))
        out.write(CSV_HEAD.joinToString(",") + "," + DETAIL_COLUMNS.joinToString(",") { it.first } + "\n")
        // Audit columns go last so tools that read the original columns by position still work.
        val blanks = List(DETAIL_COLUMNS.size) { "" }
        // Paged by row id (the order readings were saved, which is nearly time order). One cursor
        // sorted by time had no index to use, and Android re-runs a cursor's query each time its
        // 2 MB window refills, so a long history was re-sorted dozens of times per export.
        val sql = "SELECT device,time_ms,percent,charging,source,temp_tenths_c,voltage_mv,remaining_uah," +
            DETAIL_COLUMNS.joinToString(",") { it.first } + ",id FROM readings WHERE id>? ORDER BY id LIMIT $EXPORT_PAGE"
        var lastId = -1L
        var pageRows = EXPORT_PAGE
        while (pageRows == EXPORT_PAGE) readableDatabase.rawQuery(sql, arrayOf(lastId.toString())).use { c ->
            pageRows = 0
            while (c.moveToNext()) {
                pageRows++
                lastId = c.getLong(c.columnCount - 1)
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

    /**
     * Reads an export made by [exportCsv] (any version, columns matched by name) and inserts the
     * readings and experiments that aren't already here. Nothing existing is changed or removed.
     * Returns (readings added, experiments added).
     */
    fun importCsv(input: Reader): Pair<Int, Int> {
        val lines = input.buffered()
        val head = CsvCodec.parseLine(CsvCodec.readRecord(lines) ?: return 0 to 0)
        val col = head.withIndex().associate { (i, n) -> n to i }
        require("kind" in col && "timestamp_utc" in col && "percentage" in col) { "Not a Battery Lab export" }
        val existing = HashSet<String>()
        readableDatabase.rawQuery("SELECT device, source, time_ms FROM readings", null).use { c ->
            while (c.moveToNext()) existing += "${c.getString(0)}|${c.getString(1)}|${c.getLong(2)}"
        }
        val existingExp = experiments(100_000).associateBy { "${it.device}|${it.mode}|${it.startMs}" }.toMutableMap()
        var added = 0; var addedExp = 0
        val parseTime = CsvCodec.utcParser()
        val db = writableDatabase
        db.beginTransaction()
        try {
            while (true) {
                val line = CsvCodec.readRecord(lines) ?: break
                if (line.isBlank()) continue
                val f = CsvCodec.parseLine(line)
                fun get(name: String): String? = col[name]?.let { f.getOrNull(it) }?.takeIf { it.isNotEmpty() }
                val time = get("timestamp_utc")?.let(parseTime) ?: continue
                // Old exports may carry "ring:<address>"; v2 folded those into one Ring history.
                val device = get("device")?.let { if (it.startsWith("ring:")) RING_ID else it } ?: continue
                val pct = get("percentage")?.toIntOrNull()?.takeIf { it in 0..100 } ?: continue
                when (get("kind")) {
                    "reading" -> {
                        val source = get("mode_or_source") ?: continue
                        if (!existing.add("$device|$source|$time")) continue
                        val v = ContentValues().apply {
                            put("device", device); put("time_ms", time); put("percent", pct); put("source", source)
                            get("charging")?.let { put("charging", if (it == "true") 1 else 0) }
                            get("temp_c")?.toDoubleOrNull()?.let { put("temp_tenths_c", Math.round(it * 10).toInt()) }
                            get("voltage_mv")?.toIntOrNull()?.let { put("voltage_mv", it) }
                            get("remaining_uah")?.toIntOrNull()?.let { put("remaining_uah", it) }
                            for ((name, type) in DETAIL_COLUMNS) {
                                val raw = get(name) ?: continue
                                if (type == "TEXT") put(name, raw) else raw.toLongOrNull()?.let { put(name, it) }
                            }
                        }
                        db.insertOrThrow("readings", null, v); added++
                        // Commit in chunks: a long restore doesn't lock out the 15-minute job, and if
                        // the process dies part way, what was imported stays (the import skips it next time).
                        if (added % 2000 == 0) { db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction() }
                    }
                    "experiment" -> {
                        val mode = get("mode_or_source") ?: continue
                        val endMs = get("end_timestamp_utc")?.let(parseTime)
                        val endPct = get("end_percentage")?.toIntOrNull()?.takeIf { it in 0..100 }
                        val known = existingExp["$device|$mode|$time"]
                        if (known != null) {
                            // A later backup can finish a test that was restored while still running.
                            if (ExperimentEndpoint.importCompletes(known.endMs, time, endMs, endPct)) {
                                db.update("experiments", ContentValues().apply { put("end_ms", endMs); put("end_pct", endPct) },
                                    "id = ? AND end_ms IS NULL", arrayOf(known.id.toString()))
                                existingExp["$device|$mode|$time"] = known.copy(endMs = endMs, endPct = endPct); addedExp++
                            }
                            continue
                        }
                        val id = db.insertOrThrow("experiments", null, ContentValues().apply {
                            put("device", device); put("mode", mode); put("start_ms", time); put("start_pct", pct)
                            endMs?.let { put("end_ms", it) }
                            endPct?.let { put("end_pct", it) }
                        }); addedExp++
                        existingExp["$device|$mode|$time"] = Experiment(id, device, mode, time, pct, endMs, endPct)
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added to addedExp
    }

    /** Quoted CSV field; a leading = + - @ or control character is defused for spreadsheets. */
    private fun csv(value: String): String = CsvCodec.quote(value)

    companion object {
        /** Every Ring reading, from the widget or Bluetooth, shares this history id. */
        const val RING_ID = "ring:samsung_widget"
        private const val EXPORT_PAGE = 2000
        /** baseline/monitoring: Ring Bluetooth polling off vs on. ring_alone/ring_with_watch: Samsung's synergy claim. */
        val EXPERIMENT_MODES = listOf("baseline", "monitoring", "ring_alone", "ring_with_watch")
        private const val PROBE_KEEP = 3000L
        private val CSV_HEAD = listOf("kind", "device", "mode_or_source", "timestamp_utc", "percentage", "charging",
            "temp_c", "voltage_mv", "remaining_uah", "end_timestamp_utc", "end_percentage", "drop_pp_per_hour")
        /** Audit columns added in schema v3, in export order. */
        private val V3_COLUMNS = listOf(
            "current_now_raw" to "INTEGER", "current_avg_raw" to "INTEGER", "plugged" to "INTEGER",
            "status" to "INTEGER", "health" to "INTEGER", "level_raw" to "INTEGER", "scale" to "INTEGER",
            "cycle_count" to "INTEGER", "energy_raw" to "INTEGER", "elapsed_ms" to "INTEGER",
            "boot_count" to "INTEGER", "read_trigger" to "TEXT")
        private val V4_COLUMNS = listOf("android_discharge_ms" to "INTEGER", "android_charge_ms" to "INTEGER",
            "android_personalized" to "INTEGER")
        private val V5_COLUMNS = listOf("thermal_status" to "INTEGER")
        private val V6_COLUMNS = listOf("transport" to "TEXT", "display_state" to "INTEGER",
            "power_save" to "INTEGER", "device_idle" to "INTEGER", "thermal_headroom_tenths" to "INTEGER",
            "max_charging_ua" to "INTEGER", "max_charging_uv" to "INTEGER", "quality_flags" to "INTEGER",
            "rssi_dbm" to "INTEGER", "raw_extra" to "TEXT")
        private val DETAIL_COLUMNS = V3_COLUMNS + V4_COLUMNS + V5_COLUMNS + V6_COLUMNS
        @Volatile private var shared: BatteryStore? = null
        fun get(context: Context): BatteryStore =
            shared ?: synchronized(this) { shared ?: BatteryStore(context).also { shared = it } }

        fun format(ms: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))

        /** Pure merge used by [mergedWatchReadings]; exposed for tests. */
        fun mergeWatch(readings: List<Reading>): List<Reading> {
            val sorted = readings.sortedBy { it.timeMs }
            val direct = sorted.filter { it.source != SamsungWidgetSource.SOURCE }.map { it.timeMs }
            return sorted.filter { r ->
                r.source != SamsungWidgetSource.SOURCE || direct.none { kotlin.math.abs(it - r.timeMs) <= 5 * 60_000L }
            }
        }
    }
}

/** CSV helpers shared by export and restore; pure so they can be tested. */
object CsvCodec {
    fun quote(value: String): String {
        // A leading apostrophe is escaped too, so a real "'=x" survives the round trip.
        val safe = if (value.isNotEmpty() && value[0] in DEFUSE) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    /** Splits one CSV line, undoing [quote] (including its spreadsheet-safety apostrophe). */
    fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0; var quoted = false; var wasQuoted = false
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> { quoted = !quoted; wasQuoted = true }
                ch == ',' && !quoted -> { out += finish(sb.toString(), wasQuoted); sb.clear(); wasQuoted = false }
                else -> sb.append(ch)
            }
            i++
        }
        out += finish(sb.toString(), wasQuoted)
        return out
    }

    private fun finish(v: String, wasQuoted: Boolean) =
        if (wasQuoted && v.length >= 2 && v[0] == '\'' && v[1] in DEFUSE) v.substring(1) else v

    private const val DEFUSE = "=+-@\t\r'"

    /** One CSV record, which may span lines when a quoted value holds a newline; null at the end. */
    fun readRecord(reader: java.io.BufferedReader): String? {
        val first = reader.readLine() ?: return null
        if (first.count { it == '"' } % 2 == 0) return first
        val sb = StringBuilder(first)
        var quotes = first.count { it == '"' }
        while (quotes % 2 == 1) {
            val next = reader.readLine() ?: break
            sb.append('\n').append(next); quotes += next.count { it == '"' }
        }
        return sb.toString()
    }

    fun parseUtc(s: String): Long? = utcParser()(s)

    /** One reusable parser for loops; not thread-safe, so make one per caller. */
    fun utcParser(): (String) -> Long? {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return { s -> try { f.parse(s)?.time } catch (e: Exception) { null } }
    }
}

/**
 * Data-quality bits stored with each reading so later analysis can tell a fresh measurement from
 * a repeat of a cached value, and spot clock or counter glitches.
 */
object QualityFlags {
    const val NONE = 0
    /** Widget reading: when the wearable actually measured it is unknown. */
    const val UNKNOWN_SOURCE_AGE = 1 shl 0
    /** Same value and charging state as this source's previous reading. */
    const val UNCHANGED_REPEAT = 1 shl 1
    /** Wall-clock time and the monotonic clock disagree by more than a minute, or a reboot happened. */
    const val CLOCK_OR_BOOT_DISCONTINUITY = 1 shl 2
    /** The charge counter rose by more than 20 mAh without charging. */
    const val COUNTER_RESET_OR_JUMP = 1 shl 3
    /** The widget icon only loosely matched its label. */
    const val AMBIGUOUS_WIDGET_SLOT = 1 shl 4

    fun has(flags: Int?, bit: Int) = flags != null && flags and bit != 0

    /** Flags for [cur] given the previous reading from the same device and source. */
    fun assess(prev: Reading?, cur: Reading): Int {
        var f = cur.detail?.qualityFlags ?: NONE
        if (cur.source == SamsungWidgetSource.SOURCE) f = f or UNKNOWN_SOURCE_AGE
        if (prev == null) return f
        if (prev.percent == cur.percent && prev.charging == cur.charging) f = f or UNCHANGED_REPEAT
        val pe = prev.detail?.elapsedMs; val ce = cur.detail?.elapsedMs
        val pb = prev.detail?.bootCount; val cb = cur.detail?.bootCount
        if (pb != null && cb != null && pb != cb) f = f or CLOCK_OR_BOOT_DISCONTINUITY
        else if (pe != null && ce != null && kotlin.math.abs((cur.timeMs - prev.timeMs) - (ce - pe)) > 60_000L)
            f = f or CLOCK_OR_BOOT_DISCONTINUITY
        val pr = prev.remainingUah; val cr = cur.remainingUah
        if (pr != null && cr != null && cr > pr + 20_000 && cur.charging != true && prev.charging != true)
            f = f or COUNTER_RESET_OR_JUMP
        return f
    }
}
