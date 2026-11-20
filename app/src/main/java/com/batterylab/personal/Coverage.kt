package com.batterylab.personal

/**
 * How much of a time window a device was actually being read (audit #4, P0 coverage).
 * [gapMs] is the time inside the window covered by steps longer than the gap limit between two
 * readings, including the time since the last reading.
 */
data class Coverage(val fromMs: Long, val toMs: Long, val readings: Int, val gaps: Int, val gapMs: Long,
                    val longestGapMs: Long, val startsAtFirstReading: Boolean) {
    val spanMs: Long get() = toMs - fromMs
    val percent: Double get() = if (spanMs <= 0) 0.0 else 100.0 * (spanMs - gapMs) / spanMs
}

object CoverageMath {
    /**
     * Coverage of [fromMs, toMs] from reading times in any order. A step between two readings (or
     * from the last reading to [toMs]) longer than [stepMs] is a gap, and its part inside the window
     * counts against coverage. When the history starts inside the window, the window starts at the
     * first reading instead. Null when there is no reading by [toMs] or less than one step to judge.
     */
    fun of(timesMs: List<Long>, fromMs: Long, toMs: Long, stepMs: Long): Coverage? {
        val all = timesMs.filter { it <= toMs }.sorted()
        if (all.isEmpty()) return null
        val start = maxOf(fromMs, all.first())
        if (toMs - start < stepMs) return null
        // The last reading at or before the start carries coverage across the window's edge.
        val anchor = all.last { it <= start }
        val points = listOf(anchor) + all.filter { it > start } + toMs
        var gaps = 0; var gapMs = 0L; var longest = 0L
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            if (b - a <= stepMs) continue
            val inside = minOf(b, toMs) - maxOf(a, start)
            if (inside <= 0) continue
            gaps++; gapMs += inside; longest = maxOf(longest, inside)
        }
        return Coverage(start, toMs, all.count { it >= start }, gaps, gapMs, longest, start > fromMs)
    }
}
