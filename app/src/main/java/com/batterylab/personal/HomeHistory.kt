package com.batterylab.personal

/** Pure history analysis for the Home snapshot; no views, clocks, storage or radios. */
internal object HomeHistory {
    data class Life(val stretches: List<DischargeStretch>, val weeks: List<WeeklyRuntime>, val capacityMah: Double?)
    data class Board(val recent: List<Reading>, val last: Reading?, val fresh: Boolean,
                     val projection: RuntimeEstimate?, val historyRate: Double?, val other: Reading?)

    fun life(history: List<Reading>, gaps: GapLimits, weekStartMs: Long, weekOf: (Long) -> Long): Life {
        val readings = BatteryStats.fullHoldAsCharging(history)
        val stretches = BatteryMath.dischargeStretches(readings.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = gaps)
        return Life(stretches, BatteryMath.weeklyRuntime(stretches, weekOf).takeLast(6),
            BatteryMath.median(readings.filter { it.timeMs >= weekStartMs }
                .mapNotNull { BatteryMath.impliedCapacityMah(it.percent, it.remainingUah, it.charging) }))
    }

    fun board(history: List<Reading>, otherSource: Reading?, now: Long, staleAfter: Long,
              missing: Boolean, gaps: GapLimits): Board {
        val recent = BatteryStats.fullHoldAsCharging(history)
        val last = DeviceCard.shown(history)
        val dropped = last?.source == SamsungWidgetSource.SOURCE && missing
        val fresh = last != null && !dropped && now - last.timeMs <= staleAfter
        val points = recent.map { BatteryPoint(it.timeMs, it.percent, it.charging) }
        val projection = if (fresh) BatteryMath.estimate(points, gaps) else null
        val historyRate = if (fresh && projection == null && last?.charging != true) BatteryMath.historicalRate(
            BatteryMath.dischargeStretches(points, gaps = gaps).filter { it.endMs < last!!.timeMs }) else null
        val other = otherSource
            ?.takeIf { last != null && fresh && kotlin.math.abs(it.timeMs - last.timeMs) <= 30 * 60_000L && it.percent != last.percent }
        return Board(recent, last, fresh, projection, historyRate, other)
    }

    fun experiments(runs: List<Experiment>, readings: List<Reading>): List<ExperimentResult> = runs.map { e ->
        ExperimentStats.result(e, readings.filter { it.timeMs >= e.startMs && it.timeMs <= (e.endMs ?: Long.MAX_VALUE) })
    }
}
