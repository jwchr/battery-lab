package com.batterylab.personal

/** Drain for one group of discharge stretches: total points dropped over total hours. */
data class DrainGroup(val stretches: Int, val hours: Double, val drop: Int) {
    val percentPerHour: Double get() = drop / hours
}

/** Weekday and weekend drain (audit #4 roadmap). A side is null when it has too little data. */
data class WeekSplit(val weekdays: DrainGroup?, val weekends: DrainGroup?) {
    /** How much faster weekends drain than weekdays, in percent; negative means slower. Null unless both sides qualify. */
    val weekendDifferencePct: Double? get() =
        if (weekdays == null || weekends == null) null
        else 100.0 * (weekends.percentPerHour - weekdays.percentPerHour) / weekdays.percentPerHour
}

object WeekSplitMath {
    const val MIN_STRETCHES = 3
    const val MIN_HOURS = 10.0

    /**
     * Splits the stretches that end at or after [fromMs] by whether the local day of each
     * stretch's midpoint is a weekend ([isWeekend]). Null when neither side qualifies.
     */
    fun of(stretches: List<DischargeStretch>, fromMs: Long, isWeekend: (Long) -> Boolean): WeekSplit? {
        val (weekend, weekday) = stretches.filter { it.endMs >= fromMs && it.hours > 0 }
            .partition { isWeekend(it.startMs + (it.endMs - it.startMs) / 2) }
        fun group(list: List<DischargeStretch>): DrainGroup? {
            val g = DrainGroup(list.size, list.sumOf { it.hours }, list.sumOf { it.drop })
            return g.takeIf { it.stretches >= MIN_STRETCHES && it.hours >= MIN_HOURS && it.drop > 0 }
        }
        val split = WeekSplit(group(weekday), group(weekend))
        return split.takeIf { it.weekdays != null || it.weekends != null }
    }
}
