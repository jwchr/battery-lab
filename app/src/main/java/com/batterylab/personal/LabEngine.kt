package com.batterylab.personal

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * F0 — what each device can report on this phone, and in what units, worked out from the data
 * already recorded. Nothing here turns on a radio or asks for a permission.
 */
object CapabilityProbe {
    const val VERIFIED = "VERIFIED_RECORDED"
    const val DISABLED = "SUPPORTED_DISABLED"
    const val PERMISSION = "PERMISSION_NEEDED"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val UNKNOWN_AGE = "STALE_OR_UNKNOWN_AGE"

    fun firmware(): String = "${Build.VERSION.RELEASE} · ${Build.VERSION.INCREMENTAL} · patch ${Build.VERSION.SECURITY_PATCH}"

    /** Pure: builds the inventory from recorded readings (and what is known about the setup). */
    fun inventory(phone: List<Reading>, watch: List<Reading>, ring: List<Reading>, ringBleEnabled: Boolean,
                  usageGranted: Boolean, firmware: String, now: Long, gaps: GapLimits = GapLimits()): List<CapabilityRow> {
        val recent = phone.takeLast(400)
        val rows = mutableListOf<CapabilityRow>()
        fun row(key: String, kind: String, status: String, api: String, units: String, sample: String? = null,
                step: String? = null, notes: String? = null) =
            CapabilityRow(key, kind, firmware, status, api, units, sample, step, notes, now)
        fun <T> phoneField(key: String, api: String, units: String, notes: String? = null, get: (Reading) -> T?) {
            val values = recent.mapNotNull(get)
            val seen = recent.count { it.detail != null }
            rows += row(key, "phone", when {
                values.isNotEmpty() -> VERIFIED
                seen >= 3 -> UNSUPPORTED
                else -> UNKNOWN_AGE
            }, api, units, values.lastOrNull()?.toString(), minStep(values.mapNotNull { (it as? Number)?.toLong() }), notes)
        }
        phoneField("level", "EXTRA_LEVEL", "raw level") { it.detail?.levelRaw }
        phoneField("scale", "EXTRA_SCALE", "raw scale") { it.detail?.scale }
        phoneField("status", "EXTRA_STATUS", "enum (2 charging, 3 discharging, 4 not charging, 5 full)") { it.detail?.status }
        phoneField("plugged", "EXTRA_PLUGGED", "enum (0 none, 1 AC, 2 USB, 4 wireless)") { it.detail?.plugged }
        phoneField("temperature", "EXTRA_TEMPERATURE", "0.1 °C") { it.tempTenthsC }
        phoneField("voltage", "EXTRA_VOLTAGE", "mV") { it.voltageMv }
        phoneField("health", "EXTRA_HEALTH", "enum (2 good); a category, not capacity") { it.detail?.health }
        phoneField("remaining_uah", "BATTERY_PROPERTY_CHARGE_COUNTER", "µAh") { it.remainingUah }
        val units = HonestyAuditor.currentUnitsPerMa(phone, gaps)
        val unitText = when (units) { 1000.0 -> "µA (verified against counter)"; 1.0 -> "mA (verified against counter)"; else -> "unverified (nominally µA)" }
        // Each field's sign is checked on its own: a phone can fill one with the opposite sign or
        // not at all (Neon deep review, Nov 26, 2026, 1.P2o-i).
        phoneField("current_now", "BATTERY_PROPERTY_CURRENT_NOW", unitText, signConvention(phone) { it.detail?.currentNowRaw }) { it.detail?.currentNowRaw }
        phoneField("current_avg", "BATTERY_PROPERTY_CURRENT_AVERAGE", unitText, signConvention(phone) { it.detail?.currentAvgRaw }) { it.detail?.currentAvgRaw }
        phoneField("energy_nwh", "BATTERY_PROPERTY_ENERGY_COUNTER", "nWh") { it.detail?.energyRaw }
        phoneField("cycle_count", "EXTRA_CYCLE_COUNT", "cycles",
            if (recent.mapNotNull { it.detail?.cycleCount }.let { v -> v.isNotEmpty() && v.all { it == 0 } }) "Always 0 so far: probably not filled in by this phone" else null) { it.detail?.cycleCount }
        phoneField("max_charging_current", "battery broadcast extra", "µA advertised") { it.detail?.maxChargingUa?.takeIf { v -> v > 0 } }
        phoneField("max_charging_voltage", "battery broadcast extra", "µV advertised") { it.detail?.maxChargingUv?.takeIf { v -> v > 0 } }
        phoneField("discharge_prediction", "PowerManager.getBatteryDischargePrediction", "ms") { it.detail?.androidDischargeMs }
        phoneField("charge_time_remaining", "BatteryManager.computeChargeTimeRemaining", "ms") { it.detail?.androidChargeMs }
        phoneField("thermal_status", "PowerManager.getCurrentThermalStatus", "0–6") { it.detail?.thermalStatus }
        phoneField("thermal_headroom", "PowerManager.getThermalHeadroom", "×0.1 (10 = throttling)") { it.detail?.thermalHeadroomTenths }
        phoneField("power_save", "PowerManager.isPowerSaveMode", "on/off") { it.detail?.powerSave }
        phoneField("device_idle", "PowerManager.isDeviceIdleMode", "on/off") { it.detail?.deviceIdle }
        phoneField("display_state", "Display.getState", "1 off, 2 on, 3–4 Always-On") { it.detail?.displayState }
        phoneField("transport", "ConnectivityManager", "wifi / cellular / none") { it.detail?.transport }
        rows += row("app_usage", "phone", if (usageGranted) VERIFIED else PERMISSION, "UsageStatsManager", "events")

        // Watch: widget always has unknown age; Battery Lab's watch app is not installed until it reports.
        rows += row("percent_widget", "watch", if (watch.any { it.source == SamsungWidgetSource.SOURCE }) UNKNOWN_AGE else UNSUPPORTED,
            "Samsung battery widget", "%", watch.lastOrNull { it.source == SamsungWidgetSource.SOURCE }?.percent?.toString(),
            notes = "Shows what Samsung last received; the measurement time isn't exposed")
        rows += row("percent_wear_os", "watch", if (watch.any { it.source == "wear_os" }) VERIFIED else DISABLED,
            "Battery Lab watch app", "%", notes = if (watch.none { it.source == "wear_os" }) "Watch app not installed" else null)
        for (f in listOf("temperature", "voltage")) rows += row(f, "watch", DISABLED, "Battery Lab watch app", "—",
            notes = "Possible only with the watch app (not built yet)")

        // Ring: widget plus optional direct Bluetooth read; nothing else is public.
        rows += row("percent_widget", "ring", if (ring.any { it.source == SamsungWidgetSource.SOURCE }) UNKNOWN_AGE else UNSUPPORTED,
            "Samsung battery widget", "%", ring.lastOrNull { it.source == SamsungWidgetSource.SOURCE }?.percent?.toString())
        rows += row("percent_ble", "ring", when {
            ring.any { it.source == "ring_ble" } -> VERIFIED
            else -> DISABLED
        }, "Direct Bluetooth read", "%", notes = if (!ringBleEnabled) "Periodic Bluetooth reads are off" else null)
        rows += row("rssi", "ring", if (ring.any { it.detail?.rssiDbm != null }) VERIFIED else DISABLED, "BluetoothGatt.readRemoteRssi", "dBm")
        for (f in listOf("voltage", "temperature", "cycle_count", "case_percent"))
            rows += row(f, "ring", UNSUPPORTED, "—", "—", notes = "Not available through any public interface")
        return rows
    }

    /** Rebuilds and saves the inventory from the last two weeks of history. */
    fun refresh(context: Context, store: BatteryStore) {
        val from = System.currentTimeMillis() - 14 * 86_400_000L
        store.saveCapabilities(inventory(store.since("phone", from), store.mergedWatchReadings(from),
            store.since(BatteryStore.RING_ID, from), PollScheduler.ringEnabled(context), UsageSource.granted(context),
            firmware(), System.currentTimeMillis(), PollScheduler.gapLimits(context)))
    }

    /** Smallest non-zero change between consecutive values: the field's observed resolution. */
    fun minStep(values: List<Long>): String? =
        values.zipWithNext().map { (a, b) -> kotlin.math.abs(b - a) }.filter { it > 0 }.minOrNull()?.toString()

    /** Which sign the phone uses for discharge current in the field [current] reads, from readings on battery. */
    fun signConvention(phone: List<Reading>, current: (Reading) -> Int?): String? {
        val onBattery = phone.filter { it.detail?.plugged == 0 }.mapNotNull(current).filter { it != 0 }
        if (onBattery.size < 5) return null
        val negative = onBattery.count { it < 0 }.toDouble() / onBattery.size
        return when {
            negative >= 0.8 -> "Negative while discharging"
            negative <= 0.2 -> "Positive while discharging"
            else -> "Sign inconsistent"
        }
    }

    /** JSON with no addresses, names or serials; safe to share. */
    fun redactedJson(rows: List<CapabilityRow>): String {
        val arr = JSONArray()
        for (r in rows) arr.put(JSONObject().apply {
            put("field", r.fieldKey); put("device", r.deviceKind); put("status", r.status)
            put("api", r.sourceApi); put("units", r.units); r.observedMinStep?.let { put("min_step", it) }
            r.notes?.let { put("notes", it) }
        })
        return JSONObject().apply {
            put("android", Build.VERSION.RELEASE); put("sdk", Build.VERSION.SDK_INT)
            put("checked_ms", rows.maxOfOrNull { it.checkedTimeMs } ?: 0L); put("fields", arr)
        }.toString(2)
    }
}

/** F1 — notices software changes and records them as time windows in the journal. */
object ContextWatcher {
    private const val SAMSUNG_WIDGET_PKG = "com.android.settings.intelligence"

    fun check(context: Context, store: BatteryStore) {
        val prefs = PollScheduler.prefs(context)
        val now = System.currentTimeMillis()
        val versions = listOf(
            Triple("os", "os_update", "${Build.VERSION.RELEASE} ${Build.VERSION.INCREMENTAL} (patch ${Build.VERSION.SECURITY_PATCH})"),
            Triple("app", "app_update", packageVersion(context, context.packageName)),
            Triple("samsung_widget", "app_update", packageVersion(context, SAMSUNG_WIDGET_PKG))
        )
        // One write per check instead of one per key: this runs every 15 minutes.
        val edit = prefs.edit()
        for ((key, category, value) in versions) {
            if (value == null) continue
            val stored = prefs.getString("ctx_version_$key", null)
            val lastSeen = prefs.getLong("ctx_seen_$key", 0L)
            if (stored != null && stored != value) {
                val title = when (key) {
                    "os" -> "Android update: $value"
                    "app" -> "Battery Lab updated to $value"
                    else -> "Samsung battery widget updated to $value"
                }
                store.addJournal("phone", category, title, now, lastSeen.takeIf { it > 0 },
                    JSONObject().put("from", stored).put("to", value).toString())
            }
            edit.putString("ctx_version_$key", value).putLong("ctx_seen_$key", now)
        }
        edit.apply()
    }

    private fun packageVersion(context: Context, pkg: String): String? = try {
        context.packageManager.getPackageInfo(pkg, 0).versionName
    } catch (e: Exception) { null }
}

/** F2 — issues and scores forecasts from the background check. */
object ForecastKeeper {
    fun tick(context: Context, store: BatteryStore, devices: List<String>) {
        val now = System.currentTimeMillis()
        val gaps = PollScheduler.gapLimits(context)
        for (id in devices) {
            val readings = BatteryStats.fullHoldAsCharging(store.since(id, now - 4 * 86_400_000L))
            // Score anything due, from readings back to the oldest pending forecast (capped at 30
            // days): after a long gap in collection, the last 4 days would no longer reach its
            // horizons. Horizons already scored are kept, never blanked. This runs even with no
            // recent readings, so a device that stopped reporting has its forecasts closed as gaps
            // instead of left pending.
            val pending = store.forecasts(id, "PENDING")
            val scoreFrom = maxOf(now - 30 * 86_400_000L, pending.minOfOrNull { it.issuedMs } ?: now)
            val scoring = if (scoreFrom >= now - 4 * 86_400_000L) readings
                else BatteryStats.fullHoldAsCharging(store.since(id, scoreFrom))
            for ((f, s) in rescore(pending, scoring, now, gaps)) store.updateForecast(f.id, s.actual2h, s.actual4h, s.actual8h, s.status)
            val last = readings.lastOrNull() ?: continue
            // Issue at most one per hour, only while on battery with a live estimate.
            if (last.charging == true || now - last.timeMs > 30 * 60_000L) continue
            if ((store.lastForecastMs(id) ?: 0L) > now - 3_600_000L) continue
            val points = readings.map { BatteryPoint.of(it) }
            // The live estimate when this stretch has one, else the rate from finished stretches
            // (the same fallback the main screen shows as "est. (history)").
            val rate = BatteryMath.estimate(points, gaps)?.percentPerHour
                ?: BatteryMath.historicalRate(BatteryMath.dischargeStretches(points, gaps = gaps).filter { it.endMs < last.timeMs })
                ?: continue
            if (rate <= 0) continue
            val android = if (id == "phone") last.detail?.androidDischargeMs?.let { it / 3_600_000.0 } else null
            store.addForecast(Forecast(0, id, last.timeMs, last.percent, rate,
                last.percent / rate, android, if (id == "phone") last.detail?.androidPersonalized else null,
                ForecastScoring.MODEL_VERSION, null, null, null, "PENDING"))
        }
    }

    /** Pending forecasts whose score changed, with the new score. Pure; [readings] may be empty. */
    fun rescore(pending: List<Forecast>, readings: List<Reading>, now: Long, gaps: GapLimits = GapLimits()): List<Pair<Forecast, ForecastScore>> =
        pending.mapNotNull { f ->
            val s = ForecastScoring.keepScored(f, ForecastScoring.score(f, readings.filter { it.timeMs >= f.issuedMs }, now, gaps))
            (f to s).takeIf { s.status != "PENDING" || s.actual2h != f.actual2h || s.actual4h != f.actual4h }
        }
}

/** F8 — Battery Lab's own cost, logged per check. */
object Overhead {
    const val DEVICE = "overhead"

    fun logPoll(context: Context, store: BatteryStore, durationMs: Long, cpuMs: Long) {
        val db = context.getDatabasePath("battery_lab.db")
        val wal = java.io.File(db.path + "-wal")
        store.logProbe(DEVICE, "poll ms=$durationMs cpu=$cpuMs db=${db.length() + wal.length()}")
    }

    fun logBle(store: BatteryStore, durationMs: Long, ok: Boolean) {
        store.logProbe(DEVICE, "ble ms=$durationMs ok=$ok")
    }

    data class Summary(val polls: Int, val meanPollMs: Double?, val cpuSeconds: Double, val bleWakeups: Int, val dbBytes: Long?)

    /** Pure: summarizes overhead lines (time, message) from the last day. */
    fun summarize(events: List<Pair<Long, String>>): Summary {
        fun field(msg: String, key: String) = Regex("$key=(-?\\d+)").find(msg)?.groupValues?.get(1)?.toLongOrNull()
        val polls = events.filter { it.second.startsWith("poll ") }
        val ble = events.filter { it.second.startsWith("ble ") }
        return Summary(polls.size, polls.mapNotNull { field(it.second, "ms") }.takeIf { it.isNotEmpty() }?.average(),
            polls.sumOf { field(it.second, "cpu") ?: 0L } / 1000.0, ble.size,
            polls.lastOrNull()?.let { field(it.second, "db") })
    }
}
