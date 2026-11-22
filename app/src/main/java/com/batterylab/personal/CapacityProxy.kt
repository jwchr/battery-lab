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
        if (estimate.fromFullReading) {
            append("From the latest 100% reading")
            if (estimate.samples > 0) append("; ${estimate.samples} other readings")
        } else append("From ${estimate.samples} readings")
        if (!estimate.fromFullReading || estimate.samples > 0) {
            if (estimate.lowPct != null && estimate.highPct != null) append(" between ${estimate.lowPct}% and ${estimate.highPct}%")
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
        val values = used.map { it.second }
        val early = used.filter { it.first.timeMs < midpointMs }.map { it.second }
        val late = used.filter { it.first.timeMs >= midpointMs }.map { it.second }
        val earlyMah = BatteryMath.median(early)
        val lateMah = BatteryMath.median(late)
        val trend = if (early.size < 10 || late.size < 10) Trend.NOT_ENOUGH else {
            val difference = lateMah!! - earlyMah!!
            if (abs(difference) <= maxOf(iqr(early)!!, iqr(late)!!)) Trend.NO_CLEAR_CHANGE
            else if (difference < 0) Trend.LOWER else Trend.HIGHER
        }
        return CapacityProxySummary(
            estimateMah = full?.remainingUah?.div(1000.0) ?: BatteryMath.median(values),
            fromFullReading = full != null,
            samples = used.size,
            skippedCharging = recent.count { it.remainingUah != null && it.charging == true },
            skippedLowPercent = recent.count { it.remainingUah != null && it.charging != true && it.percent < 20 },
            lowPct = used.minOfOrNull { it.first.percent },
            highPct = used.maxOfOrNull { it.first.percent },
            spreadMah = iqr(values),
            trend = trend,
            earlyMah = earlyMah,
            lateMah = lateMah,
        )
    }

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
