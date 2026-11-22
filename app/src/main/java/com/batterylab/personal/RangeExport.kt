package com.batterylab.personal

import java.io.Writer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Readings-only analysis format. This deliberately shares no backup projection or identity keys. */
object RangeExport {
    const val MARKER = "# battery-lab-analysis-export-version=1"
    const val RESTORE_ERROR = "Analysis export cannot be restored; choose a full backup"
    enum class FirstRecord { BACKUP, ANALYSIS, INVALID }

    fun classify(first: String): FirstRecord {
        if (first.startsWith("# battery-lab-analysis-export-version=")) return FirstRecord.ANALYSIS
        val columns = CsvCodec.parseLine(first)
        return if (columns.containsAll(listOf("kind", "timestamp_utc", "percentage")))
            FirstRecord.BACKUP else FirstRecord.INVALID
    }

    enum class Preset { DAY, WEEK, MONTH, ALL, CUSTOM }
    data class Bounds(val fromMs: Long?, val toMs: Long?) {
        init { require(fromMs == null || toMs == null || fromMs <= toMs) { "Start date must be on or before end date" } }
        fun contains(ms: Long) = (fromMs == null || ms >= fromMs) && (toMs == null || ms < toMs)
    }
    data class Request(val bounds: Bounds, val zone: ZoneId, val includeNames: Boolean) {
        /** Explicit unbounded tokens: missing/corrupt saved state must never mean all history. */
        fun saved(): String = listOf("1", bounds.fromMs?.toString() ?: "*", bounds.toMs?.toString() ?: "*",
            zone.id, includeNames.toString()).joinToString("|")

        companion object {
            fun restore(saved: String): Request {
                val fields = saved.split('|')
                require(fields.size == 5 && fields[0] == "1") { "Export request unavailable" }
                fun bound(value: String): Long? = if (value == "*") null else value.toLong()
                return Request(Bounds(bound(fields[1]), bound(fields[2])), ZoneId.of(fields[3]),
                    fields[4].toBooleanStrict())
            }
        }
    }

    fun resolve(preset: Preset, nowMs: Long, zone: ZoneId, from: LocalDate? = null, to: LocalDate? = null): Bounds =
        when (preset) {
            Preset.ALL -> Bounds(null, null)
            Preset.CUSTOM -> {
                require(from != null && to != null) { "Choose both dates" }
                require(!from.isAfter(to)) { "Start date must be on or before end date" }
                Bounds(from.atStartOfDay(zone).toInstant().toEpochMilli(),
                    to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            }
            else -> Bounds(nowMs - when (preset) {
                Preset.DAY -> 24L
                Preset.WEEK -> 7 * 24L
                else -> 30 * 24L
            } * 3_600_000L, nowMs)
        }

    /** The same ID and timestamp predicates are used on every bounded page. */
    data class Page(val bounds: Bounds, val maxId: Long, val afterId: Long, val size: Int = 500) {
        init { require(size in 1..2000) }
        val sql: String get() = "SELECT * FROM readings WHERE id>? AND id<=?" +
            (if (bounds.fromMs != null) " AND time_ms>=?" else "") +
            (if (bounds.toMs != null) " AND time_ms<?" else "") + " ORDER BY id LIMIT $size"
        val args: Array<String> get() = listOfNotNull(afterId.toString(), maxId.toString(),
            bounds.fromMs?.toString(), bounds.toMs?.toString()).toTypedArray()
    }

    private val utcFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneId.of("UTC"))
    private val localFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx", Locale.ROOT)
    fun utc(ms: Long): String = utcFormat.format(Instant.ofEpochMilli(ms))
    fun local(ms: Long, zone: ZoneId): String = localFormat.withZone(zone).format(Instant.ofEpochMilli(ms))

    val columns = listOf("timestamp_utc", "timestamp_local", "device_alias", "source", "percentage", "charging",
        "temp_c", "voltage_mv", "remaining_uah", "current_now_raw", "current_avg_raw", "plugged", "status",
        "health", "level_raw", "scale", "cycle_count", "energy_raw", "elapsed_ms", "boot_count", "read_trigger",
        "thermal_status", "transport", "display_state", "power_save", "device_idle", "thermal_headroom_tenths",
        "max_charging_ua", "max_charging_uv", "rssi_dbm")
    private val sources = setOf("android", "wear_os", "samsung_widget", "ring_ble", "manual_widget")
    private val triggers = setOf("job", "app", "manual", "plug", "unplug", "plug_settled", "widget_check")
    private val transports = setOf("wifi", "cellular", "other", "none").let { it + it.map { s -> "$s+vpn" } }

    // Device IDs are not a trusted name field. Only exact, generic product labels already stored
    // by the companion are eligible. Unknown/custom labels and model codes stay blank, even opt-in.
    private val models = setOf("Watch", "Galaxy Watch", "Galaxy Watch Ultra", "Galaxy Watch5 Pro") +
        (4..8).map { "Galaxy Watch$it" } + listOf(4, 6, 8).map { "Galaxy Watch$it Classic" }
    fun storedName(device: String): String? = device.takeIf { it.startsWith("watch:") }
        ?.removePrefix("watch:")?.takeIf { it in models }

    class Encoder(private val out: Writer, private val request: Request) {
        private val aliases = mutableMapOf<String, String>()
        private val counts = mutableMapOf<String, Int>()

        init {
            out.write("$MARKER\n# zone_id=${request.zone.id}\n")
            out.write("# from_utc=${request.bounds.fromMs?.let(::utc) ?: "unbounded"}\n")
            out.write("# to_utc_exclusive=${request.bounds.toMs?.let(::utc) ?: "unbounded"}\n")
            out.write("# full_backup=false\n# restorable=false\n")
            out.write("# blank=unknown; time bounds=[from,to); row order=stored id\n")
            out.write("# units: percentage=percent; charging/power_save/device_idle=boolean; temp_c=C; voltage_mv=mV; remaining_uah=uAh\n")
            out.write("# units: current_*_raw=Android raw nominal uA (sign/units device-dependent); energy_raw=Android raw nominal nWh\n")
            out.write("# units: elapsed_ms=ms since boot; thermal_headroom_tenths=Android headroom x10; max_charging_ua=uA; max_charging_uv=uV; rssi_dbm=dBm\n")
            out.write("# units: level_raw/scale=raw battery level/scale; cycle_count/boot_count=count; plugged/status/health/thermal_status/display_state=Android enums\n")
            out.write((columns + if (request.includeNames) listOf("device_name") else emptyList()).joinToString(",") + "\n")
        }

        fun write(r: Reading) {
            require(request.bounds.contains(r.timeMs))
            val alias = aliases.getOrPut(r.device) {
                val kind = when {
                    r.device == "phone" || r.device.startsWith("phone:") -> "phone"
                    r.device.startsWith("watch:") -> "watch"
                    r.device.startsWith("ring:") -> "ring"
                    else -> "device"
                }
                val count = (counts[kind] ?: 0) + 1
                counts[kind] = count
                "${kind}_$count"
            }
            val d = r.detail
            val fields: List<Any?> = listOf(utc(r.timeMs), local(r.timeMs, request.zone), alias,
                r.source.takeIf { it in sources }, r.percent, r.charging, r.tempTenthsC?.div(10.0), r.voltageMv,
                r.remainingUah, d?.currentNowRaw, d?.currentAvgRaw, d?.plugged, d?.status, d?.health,
                d?.levelRaw, d?.scale, d?.cycleCount, d?.energyRaw, d?.elapsedMs, d?.bootCount,
                d?.trigger?.takeIf { it in triggers }, d?.thermalStatus, d?.transport?.takeIf { it in transports },
                d?.displayState, d?.powerSave, d?.deviceIdle, d?.thermalHeadroomTenths, d?.maxChargingUa,
                d?.maxChargingUv, d?.rssiDbm) + if (request.includeNames) listOf(storedName(r.device)) else emptyList()
            out.write(fields.joinToString(",") { value -> when (value) {
                null -> ""
                is String -> CsvCodec.quote(value)
                else -> value.toString()
            } } + "\n")
        }
    }
}
