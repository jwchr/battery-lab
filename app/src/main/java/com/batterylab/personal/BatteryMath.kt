package com.batterylab.personal

import kotlin.math.max

data class BatteryPoint(
    val timeMs: Long,
    val percent: Int,
    val charging: Boolean?
)

data class RuntimeEstimate(
    val percentPerHour: Double,
    val hoursRemaining: Double,
    val hoursFromFull: Double,
    val observationHours: Double,
    val observedDrop: Int
)

/** Observed runtime projection, never a physical capacity or battery-health measurement. */
object BatteryMath {
    private const val HOUR_MS = 3_600_000L

    fun estimate(points: List<BatteryPoint>): RuntimeEstimate? {
        val chronological = points.filter { it.percent in 0..100 }
            .sortedBy { it.timeMs }
        if (chronological.size < 2) return null
        val last = chronological.last()
        if (last.charging == true) return null

        val recent = mutableListOf(last)
        var later = last
        for (earlier in chronological.dropLast(1).asReversed()) {
            if (earlier.charging == true) break
            if (later.timeMs - earlier.timeMs > 8 * HOUR_MS) break
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

    fun experimentDrain(startMs: Long, endMs: Long?, startPct: Int, endPct: Int?): Double? {
        if (endMs == null || endPct == null || endMs <= startMs || endPct > startPct) return null
        val hours = (endMs - startMs).toDouble() / HOUR_MS
        if (hours < 0.25) return null
        return (startPct - endPct) / hours
    }
}
