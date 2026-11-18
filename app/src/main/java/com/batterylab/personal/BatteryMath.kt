package com.batterylab.personal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class BatteryPoint(
    val timeMs: Long,
    val percent: Int,
    val charging: Boolean?,
    /** Charge counter (µAh) and terminal voltage, when the source reports them. */
    val remainingUah: Int? = null,
    val voltageMv: Int? = null
) {
    companion object {
        fun of(r: Reading) = BatteryPoint(r.timeMs, r.percent, r.charging, r.remainingUah, r.voltageMv)
    }
}

data class RuntimeEstimate(
    val percentPerHour: Double,
    val hoursRemaining: Double,
    val hoursFromFull: Double,
    val observationHours: Double,
    val observedDrop: Int
)

/** One uninterrupted discharge between charges. */
data class DischargeStretch(val startMs: Long, val endMs: Long, val fromPct: Int, val toPct: Int) {
    val hours: Double get() = (endMs - startMs) / 3_600_000.0
    val drop: Int get() = fromPct - toPct
    val percentPerHour: Double get() = drop / hours
    /** Projected runtime from 100% to 0% at this stretch's drain rate. */
    val fullChargeHours: Double get() = 100.0 / percentPerHour
}

data class WeeklyRuntime(val weekStartMs: Long, val stretches: Int, val observedHours: Double, val fullChargeHours: Double)

/**
 * How far apart two readings may be and still count as one measured stretch. Scales with the
 * check interval so a longer interval doesn't turn every step into a gap.
 */
data class GapLimits(val stepMs: Long = 60 * 60_000L, val stretchMs: Long = 8 * 3_600_000L) {
    companion object {
        fun forInterval(minutes: Int) = GapLimits(
            stepMs = maxOf(60, minutes * 5 / 2) * 60_000L,
            stretchMs = maxOf(8 * 60, minutes * 3) * 60_000L)
    }
}

/** Observed runtime projection, never a physical capacity or battery-health measurement. */
object BatteryMath {
    private const val HOUR_MS = 3_600_000L

    fun estimate(points: List<BatteryPoint>, gaps: GapLimits = GapLimits()): RuntimeEstimate? {
        val chronological = withoutSpikes(points.filter { it.percent in 0..100 }.sortedBy { it.timeMs }, gaps)
        if (chronological.size < 2) return null
        val last = chronological.last()
        if (last.charging == true) return null

        val recent = mutableListOf(last)
        var later = last
        for (earlier in chronological.dropLast(1).asReversed()) {
            if (earlier.charging == true) break
            if (later.timeMs - earlier.timeMs > gaps.stretchMs) break
            if (last.timeMs - earlier.timeMs > 72 * HOUR_MS) break
            // A noticeable rise in percentage usually signals an intervening charge cycle.
            if (earlier.percent + 2 < later.percent) break
            recent.add(earlier)
            later = earlier
        }

        val first = recent.last()
        val hours = (last.timeMs - first.timeMs).toDouble() / HOUR_MS
        val drop = first.percent - last.percent
        if (hours < 1.0 || drop < 4) return null
        val rate = drop / hours
        if (rate <= 0 || rate > 100) return null
        return RuntimeEstimate(rate, max(0.0, last.percent / rate), 100.0 / rate, hours, drop)
    }

    /**
     * Weighted drain rate (% per hour) over finished discharge stretches: total drop over total
     * hours, so long stretches count for more. Null without at least 1 hour and a 4-point drop.
     */
    fun historicalRate(stretches: List<DischargeStretch>): Double? {
        val hours = stretches.sumOf { it.hours }
        val drop = stretches.sumOf { it.drop }
        return if (hours >= 1.0 && drop >= 4) drop / hours else null
    }

    /**
     * Splits history into uninterrupted discharges. A stretch ends at a charging reading, a rise
     * of more than 2 points (an unseen charge), or a gap longer than [GapLimits.stretchMs] (8 hours
     * at the default interval). Stretches shorter than [minHours] or dropping less than [minDrop]
     * points are too noisy to project and are skipped. Lone glitch readings are ignored first
     * ([withoutSpikes]).
     *
     * Known limit: a charge that starts and ends between two readings less than
     * [GapLimits.stretchMs] apart, and leaves the battery lower than before, cannot be seen.
     */
    fun dischargeStretches(points: List<BatteryPoint>, minHours: Double = 1.0, minDrop: Int = 4,
                           gaps: GapLimits = GapLimits()): List<DischargeStretch> {
        val out = mutableListOf<DischargeStretch>()
        var run = mutableListOf<BatteryPoint>()
        fun close() {
            if (run.size >= 2) {
                val s = DischargeStretch(run.first().timeMs, run.last().timeMs, run.first().percent, run.last().percent)
                if (s.hours >= minHours && s.drop >= minDrop) out += s
            }
            run = mutableListOf()
        }
        for (p in withoutSpikes(points.filter { it.percent in 0..100 }.sortedBy { it.timeMs }, gaps)) {
            if (p.charging == true) { close(); continue }
            val prev = run.lastOrNull()
            if (prev != null && (p.percent > prev.percent + 2 || p.timeMs - prev.timeMs > gaps.stretchMs)) close()
            run += p
        }
        close()
        return out
    }

    /**
     * Removes lone readings that sit more than [SPIKE_POINTS] away from both neighbours while the
     * neighbours agree within 3 points: no battery drains 75 points and recovers within one check.
     * Only when both neighbours are within [GapLimits.stepMs]: across a gap of hours, a real low
     * and an unseen charge can look the same. Charging readings, and the first and last reading,
     * are always kept. Input must be sorted.
     */
    fun withoutSpikes(sorted: List<BatteryPoint>, gaps: GapLimits = GapLimits()): List<BatteryPoint> =
        withoutSpikes(sorted, { it.percent }, { it.charging }, { it.timeMs }, gaps)

    /** [withoutSpikes] for any reading type. */
    fun <T> withoutSpikes(sorted: List<T>, percent: (T) -> Int, charging: (T) -> Boolean?, timeMs: (T) -> Long,
                          gaps: GapLimits = GapLimits()): List<T> {
        if (sorted.size < 3) return sorted
        return sorted.filterIndexed { i, p ->
            if (i == 0 || i == sorted.lastIndex || charging(p) == true) return@filterIndexed true
            val t = timeMs(p)
            if (t - timeMs(sorted[i - 1]) > gaps.stepMs || timeMs(sorted[i + 1]) - t > gaps.stepMs) return@filterIndexed true
            val a = percent(sorted[i - 1]); val b = percent(sorted[i + 1]); val x = percent(p)
            !(abs(a - b) <= 3 && abs(x - a) > SPIKE_POINTS && abs(x - b) > SPIKE_POINTS)
        }
    }

    private const val SPIKE_POINTS = 10

    /** Stretches that reach into the window starting at [fromMs], including one still under way at its start. */
    fun overlapping(stretches: List<DischargeStretch>, fromMs: Long): List<DischargeStretch> =
        stretches.filter { it.endMs >= fromMs }

    /**
     * Full-charge runtime per week, weighted by percentage points discharged so long stretches
     * count more than short ones. [weekOf] maps a time to the start of its week. A stretch that
     * crosses into another week is split by time, assuming an even drain across it; it counts
     * toward [WeeklyRuntime.stretches] in each week it touches.
     */
    fun weeklyRuntime(stretches: List<DischargeStretch>, weekOf: (Long) -> Long): List<WeeklyRuntime> {
        class Acc { var count = 0; var hours = 0.0; var drop = 0.0 }
        val weeks = sortedMapOf<Long, Acc>()
        for (s in stretches) {
            val total = (s.endMs - s.startMs).toDouble()
            if (total <= 0) continue
            var from = s.startMs
            while (from < s.endMs) {
                val week = weekOf(from)
                // 7.5 days on from a week's start always lands in the next week, DST or not.
                val next = weekOf(week + 180 * HOUR_MS)
                val to = minOf(s.endMs, next)
                val share = (to - from) / total
                weeks.getOrPut(week) { Acc() }.apply { count++; hours += s.hours * share; drop += s.drop * share }
                from = to
            }
        }
        return weeks.map { (week, a) -> WeeklyRuntime(week, a.count, a.hours, 100.0 * a.hours / a.drop) }
    }

    /**
     * Phone capacity implied by Android's charge counter at one reading. Only readings at or above
     * 20% while not charging are used, where the counter-to-percent ratio is most stable.
     */
    fun impliedCapacityMah(percent: Int, remainingUah: Int?, charging: Boolean?): Double? {
        if (remainingUah == null || remainingUah <= 0 || percent < 20 || charging == true) return null
        return remainingUah / 1000.0 * 100.0 / percent
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * When the latest value was first seen: the start of the trailing run of readings with the
     * same percent and charging state. Audit #4 A1: a widget can keep showing an old number, so
     * the time it was last read is not the time it last changed. Null for an empty list.
     */
    fun unchangedSince(readings: List<Reading>): Long? {
        val r = readings.sortedBy { it.timeMs }
        val last = r.lastOrNull() ?: return null
        var since = last.timeMs
        for (i in r.indices.reversed()) {
            if (r[i].percent != last.percent || r[i].charging != last.charging) break
            since = r[i].timeMs
        }
        return since
    }

    /** Hours left right now: the estimate made at the reading, minus the time since that reading. */
    fun timeLeftNow(hoursAtReading: Double, readingMs: Long, nowMs: Long): Double =
        max(0.0, hoursAtReading - (nowMs - readingMs).coerceAtLeast(0L).toDouble() / HOUR_MS)

    /**
     * Display value and unit for a time left: minutes (to the nearest 5) under an hour, one
     * decimal under 10 hours, whole hours above, and "probably empty" once it reaches zero.
     */
    fun timeLeftLabel(hours: Double): Pair<String, String> = when {
        hours <= 0.0 -> "0" to "probably empty"
        hours < 1.0 -> "${maxOf(5, (hours * 60 / 5).roundToInt() * 5).coerceAtMost(55)}" to "min left"
        hours < 10.0 -> {
            val v = (hours * 10).roundToInt() / 10.0
            (if (v % 1.0 == 0.0) "${v.toInt()}" else "$v") to (if (v == 1.0) "hour left" else "hours left")
        }
        else -> "${hours.roundToInt()}" to "hours left"
    }

    fun experimentDrain(startMs: Long, endMs: Long?, startPct: Int, endPct: Int?): Double? {
        if (endMs == null || endPct == null || endMs <= startMs || endPct > startPct) return null
        val hours = (endMs - startMs).toDouble() / HOUR_MS
        if (hours < 0.25) return null
        return (startPct - endPct) / hours
    }
}
