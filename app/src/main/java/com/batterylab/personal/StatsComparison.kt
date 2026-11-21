package com.batterylab.personal

data class DailyComparison(val deviceIndex: Int, val percentPerDay: Double,
                           val chargesPerWeek: Double, val hoursBetweenCharges: Double?)
data class RuntimeComparison(val deviceIndex: Int, val fullChargeHours: Double)
data class MatchedStats(val fromMs: Long, val toMs: Long,
                        val deviceIndexes: List<Int>,
                        val daily: List<DailyComparison>, val runtime: List<RuntimeComparison>)

/** Percent-and-time comparisons only. No capacity, counter, voltage or temperature inputs are used. */
object StatsComparison {
    /**
     * Use the largest device subset sharing at least two recorded days, then the longest overlap.
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
        var chosen = emptyList<Pair<Int, List<Reading>>>()
        var from = 0L
        var to = 0L
        for (mask in 1 until (1 shl histories.size)) {
            val subset = histories.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            if (subset.size < 2) continue
            val start = subset.maxOf { it.second.first().timeMs }
            val end = subset.minOf { it.second.last().timeMs }
            if (end - start < 2 * 86_400_000L) continue
            if (subset.size > chosen.size ||
                (subset.size == chosen.size && end - start > to - from)) {
                chosen = subset
                from = start
                to = end
            }
        }
        if (chosen.isEmpty()) return null
        val days = (to - from) / 86_400_000.0
        val daily = mutableListOf<DailyComparison>()
        val runtime = mutableListOf<RuntimeComparison>()
        for ((index, history) in chosen) {
            val readings = history.filter { it.timeMs in from..to }
            if (readings.size < 2) continue
            // Clock-hour buckets are unused here; a fixed zone keeps this calculation pure.
            val stats = BatteryStats.device(readings, { 0 }, gaps, { 0L })
            val starts = stats.charges.map { it.startMs }.sorted()
            daily += DailyComparison(index, stats.percentUsed / days, stats.charges.size / days * 7,
                BatteryMath.median(starts.zipWithNext { a, b -> (b - a) / 3_600_000.0 }))
            val stretches = BatteryMath.dischargeStretches(
                readings.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = gaps)
            if (stretches.isNotEmpty()) runtime += RuntimeComparison(index,
                100.0 * stretches.sumOf { it.hours } / stretches.sumOf { it.drop })
        }
        return MatchedStats(from, to, chosen.map { it.first }, daily, runtime)
    }
}
