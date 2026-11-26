package com.batterylab.personal

import kotlin.math.max
import kotlin.math.min

/**
 * One detected charge: plugged in at [fromPct], unplugged (or last seen charging) at [toPct].
 * [observed] is false when the charge was only inferred from a jump between two unplugged
 * readings, so its real start, end and speed are unknown.
 */
data class ChargeSession(val startMs: Long, val endMs: Long, val fromPct: Int, val toPct: Int, val observed: Boolean = true,
    /** False when the charge was already under way at its first reading, so [fromPct] isn't the plug-in level. */
    val plugInKnown: Boolean = true,
    /** False when no unplugged reading closed it: still charging at the last reading, or readings stopped mid-charge. */
    val ended: Boolean = true) {
    val gained: Int get() = toPct - fromPct
    val minutes: Double get() = (endMs - startMs) / 60_000.0
    /** Percent per minute; null when the charge wasn't watched or was too short to time. */
    val ratePerMinute: Double? get() = if (observed && minutes >= 5 && gained > 0) gained / minutes else null
}

/** Everything the Stats screen shows for one device, computed from its stored readings. */
data class DeviceStats(
    val readings: Int,
    val firstMs: Long?,
    val lastMs: Long?,
    /** Sum of every observed percentage-point drop: 100 = one full battery's worth. */
    val percentUsed: Int,
    val charges: List<ChargeSession>,
    val lowest: Pair<Int, Long>?,
    val hoursBelow20: Double,
    val hoursAt100: Double,
    val hoursCharging: Double,
    val hoursOnBattery: Double,
    /** Average drain (% per hour) for each local hour of the day, 0..23; null when unseen. */
    val drainByHour: List<Double?>,
    val longestOnBatteryHours: Double,
    /** Hours spent at 80% or more, plugged in or not (audit #4 roadmap: time at high charge). */
    val hoursAtOrAbove80: Double = 0.0,
) {
    val fullBatteriesUsed: Double get() = percentUsed / 100.0
    // The charge-habit values below are computed once when the stats are built (in the background for the Stats screen), not on each read.
    val averagePlugIn: Double? = charges.filter { it.plugInKnown }.map { it.fromPct }.takeIf { it.isNotEmpty() }?.average()
    val averageUnplug: Double? = charges.map { it.toPct }.takeIf { it.isNotEmpty() }?.average()
    /** Watched, ended charges unplugged before 100%, as a share of all watched, ended charges; null with none. */
    val partialChargeShare: Double? = charges.filter { it.observed && it.ended }.takeIf { it.isNotEmpty() }
        ?.let { list -> list.count { it.toPct < 100 }.toDouble() / list.size }
    val fastestCharge: ChargeSession? = charges.filter { it.ratePerMinute != null }.maxByOrNull { it.ratePerMinute!! }
    val hungriestHour: Int? get() = drainByHour.withIndex().filter { it.value != null }.maxByOrNull { it.value!! }?.index
    val calmestHour: Int? get() = drainByHour.withIndex().filter { it.value != null }.minByOrNull { it.value!! }?.index
}

data class TempStats(val avgC: Double, val minC: Double, val maxC: Double, val maxAtMs: Long,
                     val avgChargingC: Double?, val avgOnBatteryC: Double?)

object BatteryStats {
    private const val HOUR = 3_600_000.0
    private const val HOUR_MS = 3_600_000L

    /**
     * Samsung's widget drops a wearable's charging bolt once it reaches 100%, even while it sits on
     * the charger. So a run of widget readings at 100% right after a charging reading counts as still
     * charging, except the last one: the device came off somewhere between it and the first lower
     * reading, so the drain clock starts there. A run that reaches the end of the history has no
     * lower reading yet, so all of it counts as charging. Only the analysis sees this; stored
     * history and the CSV export keep exactly what was read.
     */
    fun fullHoldAsCharging(readings: List<Reading>): List<Reading> {
        val r = readings.sortedBy { it.timeMs }
        val out = r.toMutableList()
        var i = 0
        while (i < r.size) {
            if (r[i].charging != true) { i++; continue }
            var j = i + 1
            while (j < r.size && r[j].percent == 100 && r[j].charging != true && r[j].source == SamsungWidgetSource.SOURCE) j++
            for (k in i + 1 until (if (j == r.size) j else j - 1)) out[k] = r[k].copy(charging = true)
            i = maxOf(j, i + 1)
        }
        return out
    }

    /** [hourOf] maps a time to its local hour of day (0..23); injected so tests are timezone-free. */
    /** Steps longer than [GapLimits.stepMs] are gaps (phone off, job deferred) and are not counted as time. */
    /**
     * [utcOffsetMs] gives the local zone's offset from UTC at a time, so a step is split at local
     * clock-hour boundaries (the same hours [hourOf] names), including in half-hour zones.
     */
    fun device(readings: List<Reading>, hourOf: (Long) -> Int, gaps: GapLimits = GapLimits(),
               utcOffsetMs: (Long) -> Long = { java.util.TimeZone.getDefault().getOffset(it).toLong() }): DeviceStats {
        val r = BatteryMath.withoutSpikes(readings.filter { it.percent in 0..100 }.sortedBy { it.timeMs },
            { it.percent }, { it.charging }, { it.timeMs }, gaps)
        var used = 0
        // Lowest level since the last charge. A drop only counts below it, so a widget wobbling
        // 50 → 51 → 50 adds nothing, and drain across a gap still counts (audit review, 2026-11-16).
        var low: Int? = null
        var below20 = 0.0; var at100 = 0.0; var high = 0.0; var charging = 0.0; var onBattery = 0.0
        var streakStart: Long? = null; var longest = 0.0
        val hourSum = DoubleArray(24); val hourWeight = DoubleArray(24)
        val charges = mutableListOf<ChargeSession>()
        var chargeStart: Reading? = null
        var chargePeak: Reading? = null
        var plugInKnown = true

        fun closeCharge(ended: Boolean = true) {
            val s = chargeStart; val p = chargePeak
            if (s != null && p != null && p.percent > s.percent) charges += ChargeSession(s.timeMs, p.timeMs, s.percent, p.percent, plugInKnown = plugInKnown, ended = ended)
            chargeStart = null; chargePeak = null; plugInKnown = true
        }

        for (i in r.indices) {
            val cur = r[i]
            val prev = r.getOrNull(i - 1)
            val isCharging = cur.charging == true
            if (prev != null) {
                val dt = cur.timeMs - prev.timeMs
                val prevCharging = prev.charging == true
                val drop = when {
                    isCharging -> 0
                    // Unplugged within one check: what it lost since the charging reading is real use.
                    prevCharging -> if (dt <= gaps.stepMs) max(0, prev.percent - cur.percent) else 0
                    else -> max(0, (low ?: prev.percent) - cur.percent)
                }
                used += drop
                if (dt <= gaps.stepMs) {
                    val h = dt / HOUR
                    if (prev.percent < 20) below20 += h
                    if (prev.percent >= 80) high += h
                    if (prev.percent == 100 && prevCharging) at100 += h
                    if (prevCharging) charging += h else onBattery += h
                    // Rising steps (a wobble's up-leg, an unseen charge) aren't drain time.
                    if (!prevCharging && !isCharging && h > 0 && cur.percent <= prev.percent) {
                        // Share the drop across the clock hours the interval spans, by time in each.
                        var t = prev.timeMs
                        while (t < cur.timeMs) {
                            val off = utcOffsetMs(t)
                            val next = minOf(cur.timeMs, (Math.floorDiv(t + off, HOUR_MS) + 1) * HOUR_MS - off)
                            val part = (next - t).toDouble() / dt
                            val hr = hourOf(t)
                            hourSum[hr] += drop * part; hourWeight[hr] += h * part
                            t = next
                        }
                    }
                }
                // Charges: watched (charging flag) or inferred (a jump up of more than 2 points).
                // After a gap the plug-in time and level are unknown, so the timed charge starts at
                // this reading (also when the reading before the gap was charging too).
                if (isCharging && (!prevCharging || dt > gaps.stepMs)) {
                    closeCharge(ended = false)   // only an open charge here: readings stopped mid-charge
                    if (dt > gaps.stepMs) { chargeStart = cur; plugInKnown = false } else chargeStart = prev
                    chargePeak = cur
                }
                // ">" not ">=": time parked at the peak afterwards isn't charging speed.
                else if (isCharging) { if (cur.percent > (chargePeak?.percent ?: -1)) chargePeak = cur }
                else if (prevCharging) closeCharge()
                else if (cur.percent > prev.percent + 2) charges += ChargeSession(prev.timeMs, cur.timeMs, prev.percent, cur.percent, observed = false)
                // Longest unbroken time on battery: ends at a charge, a jump up, or a gap.
                val breaks = isCharging || cur.percent > prev.percent + 2 || dt > gaps.stretchMs
                if (breaks) streakStart = null
                else if (!prevCharging) {
                    if (streakStart == null) streakStart = prev.timeMs
                    longest = max(longest, (cur.timeMs - streakStart!!) / HOUR)
                }
            }
            // History that starts mid-charge: time the charge from its first reading.
            if (prev == null && isCharging) { chargeStart = cur; chargePeak = cur; plugInKnown = false }
            low = when {
                isCharging -> null
                prev != null && cur.percent > prev.percent + 2 -> cur.percent   // unseen charge: new baseline
                else -> minOf(low ?: cur.percent, cur.percent)
            }
        }
        closeCharge(ended = false)   // still charging at the last reading
        val lowest = r.minByOrNull { it.percent }?.let { it.percent to it.timeMs }
        return DeviceStats(r.size, r.firstOrNull()?.timeMs, r.lastOrNull()?.timeMs, used, charges, lowest,
            below20, at100, charging, onBattery,
            (0 until 24).map { if (hourWeight[it] >= 0.5) hourSum[it] / hourWeight[it] else null }, longest, high)
    }

    fun temperature(readings: List<Reading>): TempStats? {
        val t = readings.filter { it.tempTenthsC != null && it.tempTenthsC in 0..700 }
        if (t.isEmpty()) return null
        val c = t.map { it.tempTenthsC!! / 10.0 }
        val hottest = t.maxByOrNull { it.tempTenthsC!! }!!
        return TempStats(c.average(), c.min(), c.max(), hottest.timeMs,
            t.filter { it.charging == true }.map { it.tempTenthsC!! / 10.0 }.takeIf { it.isNotEmpty() }?.average(),
            t.filter { it.charging == false }.map { it.tempTenthsC!! / 10.0 }.takeIf { it.isNotEmpty() }?.average())
    }

    /** Energy in watt-hours for [percentUsed] of a [capacityMah] battery at a nominal 3.87 V. */
    fun wattHours(percentUsed: Int, capacityMah: Double): Double = percentUsed / 100.0 * capacityMah * 3.87 / 1000.0

    fun clampPct(v: Double) = min(100.0, max(0.0, v))
}
