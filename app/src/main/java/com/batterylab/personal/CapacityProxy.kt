package com.batterylab.personal

import kotlin.math.abs

data class CapacityProxySummary(
    val estimateMah: Double?,
    val fromFullReading: Boolean,
    val samples: Int,
    val skippedCharging: Int,
    val skippedLowPercent: Int,
    val lowPct: Int?,
    val highPct: Int?,
    val otherSamples: Int,
    val otherLowPct: Int?,
    val otherHighPct: Int?,
    val spreadMah: Double?,
    val trend: Trend,
    val earlyMah: Double?,
    val lateMah: Double?,
)

enum class Trend { NOT_ENOUGH, NO_CLEAR_CHANGE, LOWER, HIGHER }

/** Describes the charge counter's proxy for full capacity, not independently measured health. */
object CapacityProxy {
    fun capacityCaption(percent: Int?, spec: PhoneSpec?, formatMah: (Double) -> String): String = buildString {
        if (percent != null) append("Roughly $percent% of the ${formatMah(spec!!.typicalMah)} mAh advertised")
        else if (spec != null) append("Advertised: ${formatMah(spec.typicalMah)} mAh")
        if (spec != null) append(" (${formatMah(spec.ratedMah)} mAh rated minimum).")
        if (percent != null) append(" This is a charge-counter estimate, not a battery health test.")
    }

    fun readingsCaption(estimate: CapacityProxySummary, formatMah: (Double) -> String): String = buildString {
        val samples = if (estimate.fromFullReading) estimate.otherSamples else estimate.samples
        val lowPct = if (estimate.fromFullReading) estimate.otherLowPct else estimate.lowPct
        val highPct = if (estimate.fromFullReading) estimate.otherHighPct else estimate.highPct
        if (estimate.fromFullReading) {
            append("From the latest 100% reading")
            if (samples > 0) append("; $samples other reading${if (samples == 1) "" else "s"}")
        } else append("From ${estimate.samples} readings")
        if (!estimate.fromFullReading || samples > 0) {
            if (lowPct != null && highPct != null) append(" between $lowPct% and $highPct%")
            append(", not charging")
        }
        append(" (${estimate.skippedCharging} skipped while charging, ${estimate.skippedLowPercent} below 20%).")
        estimate.spreadMah?.let { append(" Spread ±${formatMah(it / 2)} mAh.") }
    }

    fun summarize(readings: List<Reading>, nowMs: Long): CapacityProxySummary {
        val windowMs = 30 * 86_400_000L
        val startMs = nowMs - windowMs
        val midpointMs = startMs + windowMs / 2
        val recent = readings.filter { it.device == "phone" && it.timeMs in startMs..nowMs }
        // Preserve the headline's full-reading preference, including a full reading while charging.
        val full = recent.filter { it.percent == 100 && (it.remainingUah ?: 0) > 0 }.maxByOrNull { it.timeMs }
        val used = recent.mapNotNull { r ->
            BatteryMath.impliedCapacityMah(r.percent, r.remainingUah, r.charging)?.let { r to it }
        }
        val other = used.filter { it.first !== full }
        val values = used.map { it.second }
        // One value per day: polls 15 minutes apart move together, so 40 of them on one day are
        // one observation, not 40 (Neon deep review, Nov 26, 2026, 1.P2e).
        // Whole days go to one half (the day holding the midpoint goes to the later half), so two
        // polls minutes apart across the midpoint are not two days (Neon and Flash review of #96).
        val midDay = Math.floorDiv(midpointMs, DAY_MS)
        val byDay = used.groupBy { Math.floorDiv(it.first.timeMs, DAY_MS) }
        val early = dailyMedians(byDay.filterKeys { it < midDay })
        val late = dailyMedians(byDay.filterKeys { it >= midDay })
        val earlyMah = BatteryMath.median(early)
        val lateMah = BatteryMath.median(late)
        val trend = if (early.size < MIN_DAYS || late.size < MIN_DAYS) Trend.NOT_ENOUGH else {
            val difference = lateMah!! - earlyMah!!
            // A flat counter has no spread, so also require a change big enough to matter.
            val floor = maxOf(iqr(early)!!, iqr(late)!!, earlyMah * MIN_CHANGE_FRACTION)
            if (abs(difference) <= floor) Trend.NO_CLEAR_CHANGE
            else if (difference < 0) Trend.LOWER else Trend.HIGHER
        }
        return CapacityProxySummary(
            estimateMah = full?.remainingUah?.div(1000.0) ?: BatteryMath.median(values),
            fromFullReading = full != null,
            samples = used.size,
            skippedCharging = recent.count { (it.remainingUah ?: 0) > 0 && it.charging == true },
            skippedLowPercent = recent.count { (it.remainingUah ?: 0) > 0 && it.charging != true && it.percent < 20 },
            lowPct = used.minOfOrNull { it.first.percent },
            highPct = used.maxOfOrNull { it.first.percent },
            otherSamples = other.size,
            otherLowPct = other.minOfOrNull { it.first.percent },
            otherHighPct = other.maxOfOrNull { it.first.percent },
            spreadMah = iqr(values),
            trend = trend,
            earlyMah = earlyMah,
            lateMah = lateMah,
        )
    }

    /** Each half needs readings on this many different days before a trend is named. */
    internal const val MIN_DAYS = 4
    /** Changes of 3 percent or less are within the counter's usual drift with temperature and calibration. */
    internal const val MIN_CHANGE_FRACTION = 0.03

    private const val DAY_MS = 86_400_000L

    /** The median capacity of each calendar day (UTC) that has readings. */
    private fun dailyMedians(days: Map<Long, List<Pair<Reading, Double>>>): List<Double> =
        days.values.mapNotNull { day -> BatteryMath.median(day.map { it.second }) }

    /** Linearly interpolated quartiles, matching the other Stats spread calculations. */
    private fun iqr(values: List<Double>): Double? {
        if (values.size < 4) return null
        val sorted = values.sorted()
        fun quantile(q: Double): Double {
            val position = q * sorted.lastIndex
            val lower = position.toInt()
            val upper = minOf(lower + 1, sorted.lastIndex)
            return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
        }
        return quantile(0.75) - quantile(0.25)
    }
}
