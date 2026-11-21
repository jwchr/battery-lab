package com.batterylab.personal

data class DailyComparison(val deviceIndex: Int, val percentPerDay: Double,
                           val chargesPerWeek: Double, val hoursBetweenCharges: Double?)
data class RuntimeComparison(val deviceIndex: Int, val fullChargeHours: Double)
data class MatchedStats(val fromMs: Long, val toMs: Long,
                        val daily: List<DailyComparison>, val runtime: List<RuntimeComparison>)

/** Percent-and-time comparisons only. No capacity, counter, voltage or temperature inputs are used. */
object StatsComparison {
    /**
     * Use the intersection of recorded history, never one device's lifetime against another's.
     * Keep only real readings inside it; do not invent boundary readings or extrapolate beyond
     * the device that stopped reporting first. Empty/single-reading histories cannot participate.
     * Coverage inside the shared calendar period can still differ, so the UI calls that out.
     */
    fun matched(data: List<List<Reading>>, gaps: GapLimits): MatchedStats? {
        val histories = data.mapIndexedNotNull { index, readings ->
            readings.filter { it.percent in 0..100 }.sortedBy { it.timeMs }
                .takeIf { it.size >= 2 }?.let { index to it }
        }
        if (histories.size < 2) return null
        val from = histories.maxOf { it.second.first().timeMs }
        val to = histories.minOf { it.second.last().timeMs }
        if (to <= from) return null
        val days = (to - from) / 86_400_000.0
        val daily = mutableListOf<DailyComparison>()
        val runtime = mutableListOf<RuntimeComparison>()
        for ((index, history) in histories) {
            val readings = history.filter { it.timeMs in from..to }
            if (readings.size < 2) continue
            if (days >= 2.0) {
                // Clock-hour buckets are unused here; a fixed zone keeps this calculation pure.
                val stats = BatteryStats.device(readings, { 0 }, gaps, { 0L })
                val starts = stats.charges.map { it.startMs }.sorted()
                daily += DailyComparison(index, stats.percentUsed / days, stats.charges.size / days * 7,
                    BatteryMath.median(starts.zipWithNext { a, b -> (b - a) / 3_600_000.0 }))
            }
            val stretches = BatteryMath.dischargeStretches(
                readings.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = gaps)
            if (stretches.isNotEmpty()) runtime += RuntimeComparison(index,
                100.0 * stretches.sumOf { it.hours } / stretches.sumOf { it.drop })
        }
        return MatchedStats(from, to, daily, runtime)
    }
}
