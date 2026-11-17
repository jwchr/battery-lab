package com.batterylab.personal

import android.os.BatteryManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Phone battery capacity as the fuel gauge implies it, from two independent methods.
 * Both read Android's own charge counter, so they measure what the gauge believes, not an
 * external lab reference.
 */
data class CapacityEstimate(
    /** Median of the charge counter at saturated 100% plateaus. */
    val saturatedPeakMah: Double?,
    val peakSamples: Int,
    /** Median of per-stretch OLS slopes of counter vs percent (15–98%), × 100. */
    val slopeCapacityMah: Double?,
    val slopeRmseMah: Double?,
    val slopeSamples: Int,
    /** Median over all samples from both methods, with their interquartile range. */
    val combinedMah: Double?,
    val iqrMah: Double?,
    val sampleCount: Int
)

/** Six checks of whether the percentages Samsung and Android show line up with the hardware counters. */
data class HonestyAudit(
    // A: top of charge
    val medianCvTaperAddedMah: Double?,
    val medianCvTaperMinutes: Double?,
    val medianTopHoldBufferMah: Double?,
    val topHoldMinutes: Double?,
    val topBufferRatio: Double?,
    // B: gauge linearity
    val upperBandMahPerPct: Double?,
    val midBandMahPerPct: Double?,
    val lowerBandMahPerPct: Double?,
    val gaugeSkewRatio: Double?,
    val decileMahPerPct: Map<Int, Double>,
    // C: bottom cliff
    val bottom15DrainRatePpPerHr: Double?,
    val midBandDrainRatePpPerHr: Double?,
    val bottom15CliffRatio: Double?,
    // D: resting voltage and internal resistance (need verified current units)
    val currentUnitsPerMa: Double?,
    val restingVoltage80Mv: Int?,
    val restingVoltage50Mv: Int?,
    val restingVoltage20Mv: Int?,
    val internalResistanceMilliohms: Int?,
    // E: cycle counter
    val reportedCycleDelta: Int?,
    val coulombThroughputCycles: Double?,
    val pctPointThroughputCycles: Double?,
    // F: Samsung widget lag and error
    val phoneWidgetPairs: Int,
    val phoneWidgetMeanErrorPp: Double?,
    val phoneWidgetMaxAbsErrorPp: Int?,
    val phoneWidgetLagMinutesP50: Double?,
    val watchWidgetVsCompanionPairs: Int,
    val watchWidgetMeanErrorPp: Double?,
    val ringWidgetVsBlePairs: Int,
    val ringWidgetMeanErrorPp: Double?
)

object HonestyAuditor {
    const val MODEL_VERSION = "audit-1"
    private const val HOUR = 3_600_000.0

    // ---- current units ------------------------------------------------------------------

    /**
     * How many raw current units make one milliamp on this phone (1000 for µA, 1 for mA), found
     * by comparing reported discharge current with how fast the charge counter actually falls.
     * Null until there are at least 5 clean discharge steps and the ratio sits near 1 or 1000.
     */
    fun currentUnitsPerMa(readings: List<Reading>, gaps: GapLimits = GapLimits()): Double? {
        val ratios = mutableListOf<Double>()
        for ((a, b) in steps(readings, gaps)) {
            val ra = a.remainingUah ?: continue; val rb = b.remainingUah ?: continue
            if (a.charging == true || b.charging == true) continue
            val hours = (b.timeMs - a.timeMs) / HOUR
            if (hours < 0.25) continue
            val counterMa = (ra - rb) / 1000.0 / hours
            if (counterMa < 20) continue
            val raw = listOfNotNull(a.detail?.currentAvgRaw ?: a.detail?.currentNowRaw,
                b.detail?.currentAvgRaw ?: b.detail?.currentNowRaw).map { abs(it.toDouble()) }
            if (raw.size < 2) continue
            ratios += raw.average() / counterMa
        }
        if (ratios.size < 5) return null
        val m = BatteryMath.median(ratios) ?: return null
        return when {
            m in 300.0..3000.0 -> 1000.0
            m in 0.3..3.0 -> 1.0
            else -> null
        }
    }

    // ---- capacity -----------------------------------------------------------------------

    fun estimatePhoneCapacity(readings: List<Reading>, gaps: GapLimits = GapLimits()): CapacityEstimate {
        val r = readings.sortedBy { it.timeMs }
        val peaks = saturatedPeaks(r, gaps)
        val slopes = slopeCapacities(r, gaps)
        val all = peaks + slopes.map { it.first }
        val sorted = all.sorted()
        val iqr = if (sorted.size >= 4) quantile(sorted, 0.75) - quantile(sorted, 0.25) else null
        return CapacityEstimate(
            saturatedPeakMah = BatteryMath.median(peaks), peakSamples = peaks.size,
            slopeCapacityMah = BatteryMath.median(slopes.map { it.first }),
            slopeRmseMah = BatteryMath.median(slopes.map { it.second }), slopeSamples = slopes.size,
            combinedMah = BatteryMath.median(all), iqrMah = iqr, sampleCount = all.size)
    }

    /** Charge-counter value (mAh) at each 100% plateau that is genuinely full, not a mid-taper touch. */
    fun saturatedPeaks(r: List<Reading>, gaps: GapLimits): List<Double> {
        val out = mutableListOf<Double>()
        var run = mutableListOf<Reading>()
        fun close() {
            if (run.isNotEmpty()) {
                val full = run.any { it.detail?.status == BatteryManager.BATTERY_STATUS_FULL }
                val long = run.last().timeMs - run.first().timeMs >= 15 * 60_000L
                val unplugged = run.zipWithNext().any { (a, b) -> a.charging == true && b.charging == false }
                if (full || long || unplugged) {
                    run.mapNotNull { it.remainingUah }.map { it / 1000.0 }.filter { it in 2000.0..7000.0 }
                        .maxOrNull()?.let { out += it }
                }
            }
            run = mutableListOf()
        }
        for (x in r) {
            if (x.percent != 100) { close(); continue }
            if (run.isNotEmpty() && x.timeMs - run.last().timeMs > gaps.stepMs) close()
            run += x
        }
        close()
        return out
    }

    /** (capacity mAh, RMSE mAh) per qualifying discharge stretch. */
    fun slopeCapacities(r: List<Reading>, gaps: GapLimits): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        for (stretch in dischargeRuns(r, gaps)) {
            // One point per percent level (the first moment it showed that level), 15..98%.
            val pts = stretch.filter { it.percent in 15..98 && it.remainingUah != null }.distinctBy { it.percent }
            if (pts.size < 6 || pts.maxOf { it.percent } - pts.minOf { it.percent } < 30) continue
            val fit = ols(pts.map { it.percent.toDouble() }, pts.map { it.remainingUah!! / 1000.0 }) ?: continue
            val cap = fit.first * 100.0
            if (cap in 1000.0..10_000.0) out += cap to fit.third
        }
        return out
    }

    // ---- the audit ----------------------------------------------------------------------

    fun audit(
        phone: List<Reading>,
        phoneWidget: List<Reading>,
        watch: List<Reading>,
        ring: List<Reading>,
        capacityMah: Double?,
        gaps: GapLimits = GapLimits()
    ): HonestyAudit {
        val r = phone.sortedBy { it.timeMs }
        // Every unplugged step that didn't rise, flat ones included. Dropping the flat steps (where
        // time passed and the counter fell but the percent hadn't ticked yet) overstated drain
        // rates and understated mAh per 1% (audit review, 2026-11-16).
        val transitions = steps(r, gaps).filter { (a, b) -> a.charging != true && b.charging != true && b.percent <= a.percent }

        // B: mAh per 1% by band: total counter drop over total percent drop for steps in the band.
        val perStep = transitions.mapNotNull { (a, b) ->
            val ra = a.remainingUah ?: return@mapNotNull null; val rb = b.remainingUah ?: return@mapNotNull null
            if (rb > ra + 1000) return@mapNotNull null   // counter reset or recalibration
            Triple((a.percent + b.percent) / 2.0, max(0, ra - rb) / 1000.0, a.percent - b.percent)
        }
        fun ratio(sel: List<Triple<Double, Double, Int>>): Double? {
            val pct = sel.sumOf { it.third }
            if (pct < 3) return null
            return (sel.sumOf { it.second } / pct).takeIf { it > 0 && it <= 500 }
        }
        fun band(lo: Int, hi: Int) = ratio(perStep.filter { it.first >= lo && (it.first < hi || hi == 95 && it.first <= hi) })
        val upper = band(70, 95); val mid = band(40, 70); val lower = band(15, 40)
        val deciles = (1..9).mapNotNull { d -> ratio(perStep.filter { (it.first / 10).toInt() == d })?.let { d * 10 to it } }.toMap()

        // A: what happens around 100%.
        val taper = cvTaper(r, gaps)
        val hold = topHold(r, gaps)
        val holdMah = BatteryMath.median(hold.mapNotNull { it.second })

        // C: drain speed at the bottom vs the middle.
        fun rate(lo: Double, hi: Double): Double? {
            val sel = transitions.filter { (a, b) -> (a.percent + b.percent) / 2.0 in lo..hi }
            val hours = sel.sumOf { (a, b) -> (b.timeMs - a.timeMs) / HOUR }
            val drop = sel.sumOf { (a, b) -> a.percent - b.percent }
            return if (hours >= 0.5 && drop >= 2) drop / hours else null
        }
        val bottom = rate(1.0, 15.0); val middle = rate(20.0, 80.0)

        // D: needs current in known units.
        val unitsPerMa = currentUnitsPerMa(r, gaps)
        fun currentMa(x: Reading) = unitsPerMa?.let { u -> (x.detail?.currentNowRaw ?: x.detail?.currentAvgRaw)?.let { it / u } }
        fun resting(lo: Int, hi: Int): Int? {
            if (unitsPerMa == null) return null
            val v = r.filter { x ->
                x.percent in lo..hi && x.charging != true && x.voltageMv != null &&
                    (x.detail?.displayState?.let { it != 2 } ?: false) &&
                    (currentMa(x)?.let { abs(it) <= 250 } ?: false) &&
                    (x.tempTenthsC?.let { it in 200..300 } ?: false)
            }.map { it.voltageMv!!.toDouble() }
            return BatteryMath.median(v)?.toInt()
        }
        val resistance = if (unitsPerMa == null) null else {
            val samples = r.zipWithNext().mapNotNull { (a, b) ->
                if (a.charging == true || b.charging == true || abs(a.percent - b.percent) > 1) return@mapNotNull null
                if (b.timeMs - a.timeMs > 30 * 60_000L) return@mapNotNull null
                val va = a.voltageMv ?: return@mapNotNull null; val vb = b.voltageMv ?: return@mapNotNull null
                val ia = currentMa(a) ?: return@mapNotNull null; val ib = currentMa(b) ?: return@mapNotNull null
                val di = abs(ia) - abs(ib)
                if (abs(di) < 200) return@mapNotNull null
                // Higher load sags the terminal voltage: R = -ΔV / Δ|I|.
                val ohms = -(va - vb) / (di / 1000.0) / 1000.0
                if (ohms in 0.005..1.0) ohms * 1000 else null
            }
            BatteryMath.median(samples)?.toInt()
        }

        // E: cycle counter vs throughput.
        val cycles = r.mapNotNull { it.detail?.cycleCount }
        val dischargedMah = transitions.sumOf { (a, b) ->
            val ra = a.remainingUah; val rb = b.remainingUah
            if (ra != null && rb != null && ra > rb) (ra - rb) / 1000.0 else 0.0
        }
        val dischargedPct = transitions.sumOf { (a, b) -> (a.percent - b.percent).toDouble() }

        // F: Samsung's widget vs other sources.
        val phonePairs = pair(phoneWidget, r, 2 * 60_000L)
        val watchPairs = pair(watch.filter { it.source == SamsungWidgetSource.SOURCE }, watch.filter { it.source == "wear_os" }, 5 * 60_000L)
        val ringPairs = pair(ring.filter { it.source == SamsungWidgetSource.SOURCE }, ring.filter { it.source == "ring_ble" }, 5 * 60_000L)

        return HonestyAudit(
            medianCvTaperAddedMah = BatteryMath.median(taper.mapNotNull { it.second }),
            medianCvTaperMinutes = BatteryMath.median(taper.map { it.first }),
            medianTopHoldBufferMah = holdMah,
            topHoldMinutes = BatteryMath.median(hold.map { it.first }),
            topBufferRatio = if (holdMah != null && mid != null && mid > 0) holdMah / mid else null,
            upperBandMahPerPct = upper, midBandMahPerPct = mid, lowerBandMahPerPct = lower,
            gaugeSkewRatio = if (upper != null && lower != null && lower > 0) upper / lower else null,
            decileMahPerPct = deciles,
            bottom15DrainRatePpPerHr = bottom, midBandDrainRatePpPerHr = middle,
            bottom15CliffRatio = if (bottom != null && middle != null && middle > 0) bottom / middle else null,
            currentUnitsPerMa = unitsPerMa,
            restingVoltage80Mv = resting(78, 82), restingVoltage50Mv = resting(48, 52), restingVoltage20Mv = resting(18, 22),
            internalResistanceMilliohms = resistance,
            reportedCycleDelta = if (cycles.size >= 2) cycles.last() - cycles.first() else null,
            coulombThroughputCycles = capacityMah?.takeIf { it > 0 && dischargedMah > 0 }?.let { dischargedMah / it },
            pctPointThroughputCycles = if (dischargedPct > 0) dischargedPct / 100.0 else null,
            phoneWidgetPairs = phonePairs.size,
            phoneWidgetMeanErrorPp = phonePairs.takeIf { it.isNotEmpty() }?.map { it.toDouble() }?.average(),
            phoneWidgetMaxAbsErrorPp = phonePairs.maxOfOrNull { abs(it) },
            phoneWidgetLagMinutesP50 = BatteryMath.median(widgetLags(r, phoneWidget)),
            watchWidgetVsCompanionPairs = watchPairs.size,
            watchWidgetMeanErrorPp = watchPairs.takeIf { it.isNotEmpty() }?.map { it.toDouble() }?.average(),
            ringWidgetVsBlePairs = ringPairs.size,
            ringWidgetMeanErrorPp = ringPairs.takeIf { it.isNotEmpty() }?.map { it.toDouble() }?.average()
        )
    }

    /** (minutes charging at "100%", mAh added after first showing 100%) per charge that reached 100%. */
    fun cvTaper(r: List<Reading>, gaps: GapLimits): List<Pair<Double, Double?>> {
        val out = mutableListOf<Pair<Double, Double?>>()
        var i = 0
        while (i < r.size) {
            if (!(r[i].percent == 100 && r[i].charging == true && (i == 0 || r[i - 1].percent < 100))) { i++; continue }
            var j = i
            while (j + 1 < r.size && r[j + 1].percent == 100 && r[j + 1].charging == true &&
                r[j + 1].timeMs - r[j].timeMs <= gaps.stepMs) j++
            val minutes = (r[j].timeMs - r[i].timeMs) / 60_000.0
            val start = r[i].remainingUah; val peak = r.subList(i, j + 1).mapNotNull { it.remainingUah }.maxOrNull()
            if (j > i) out += minutes to (if (start != null && peak != null) (peak - start) / 1000.0 else null)
            i = j + 1
        }
        return out
    }

    /** (minutes unplugged still showing 100%, mAh used in that time) per unplug at 100%. */
    fun topHold(r: List<Reading>, gaps: GapLimits): List<Pair<Double, Double?>> {
        val out = mutableListOf<Pair<Double, Double?>>()
        for (i in 1 until r.size) {
            if (!(r[i - 1].charging == true && r[i].charging == false && r[i].percent == 100)) continue
            var j = i
            while (j + 1 < r.size && r[j + 1].charging != true && r[j + 1].timeMs - r[j].timeMs <= gaps.stepMs && r[j + 1].percent == 100) j++
            val next = r.getOrNull(j + 1) ?: continue
            if (next.charging == true || next.percent != 99 || next.timeMs - r[j].timeMs > gaps.stepMs) continue
            val minutes = (next.timeMs - r[i].timeMs) / 60_000.0
            val a = r[i].remainingUah; val b = next.remainingUah
            out += minutes to (if (a != null && b != null && a > b) (a - b) / 1000.0 else null)
        }
        return out
    }

    /** Widget minus reference percentage for readings within [windowMs] of each other. */
    fun pair(widget: List<Reading>, reference: List<Reading>, windowMs: Long): List<Int> {
        if (widget.isEmpty() || reference.isEmpty()) return emptyList()
        val ref = reference.sortedBy { it.timeMs }
        val times = ref.map { it.timeMs }.toLongArray()
        return widget.mapNotNull { w ->
            var k = java.util.Arrays.binarySearch(times, w.timeMs)
            if (k < 0) k = -k - 1
            val near = listOfNotNull(ref.getOrNull(k - 1), ref.getOrNull(k)).minByOrNull { abs(it.timeMs - w.timeMs) }
            near?.takeIf { abs(it.timeMs - w.timeMs) <= windowMs }?.let { w.percent - it.percent }
        }
    }

    /** Minutes from Android showing a new (lower) percentage to the widget first showing it or lower. */
    fun widgetLags(android: List<Reading>, widget: List<Reading>): List<Double> {
        if (widget.isEmpty()) return emptyList()
        val w = widget.sortedBy { it.timeMs }
        val out = mutableListOf<Double>()
        // Drops come in time order, so the window's start only moves forward, and nothing past
        // 360 minutes is kept: this stays linear instead of rescanning all widget history per drop.
        var start = 0
        for ((a, b) in android.sortedBy { it.timeMs }.zipWithNext()) {
            if (b.percent >= a.percent || b.charging == true) continue
            while (start < w.size && w[start].timeMs < b.timeMs - 60_000L) start++
            var i = start
            var seen: Reading? = null
            while (i < w.size && w[i].timeMs <= b.timeMs + 360 * 60_000L) {
                if (w[i].percent <= b.percent) { seen = w[i]; break }
                i++
            }
            if (seen == null) continue
            val lag = (seen.timeMs - b.timeMs) / 60_000.0
            if (lag in -1.0..360.0) out += maxOf(0.0, lag)
        }
        return out
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Consecutive reading pairs no further apart than the step limit. */
    fun steps(r: List<Reading>, gaps: GapLimits): List<Pair<Reading, Reading>> =
        r.sortedBy { it.timeMs }.zipWithNext().filter { (a, b) -> b.timeMs - a.timeMs in 1..gaps.stepMs }

    /** Uninterrupted non-charging runs (no gap, no rise, no counter jump). */
    fun dischargeRuns(r: List<Reading>, gaps: GapLimits): List<List<Reading>> {
        val out = mutableListOf<List<Reading>>()
        var run = mutableListOf<Reading>()
        for (x in r) {
            val prev = run.lastOrNull()
            val breaks = x.charging == true || prev == null || x.timeMs - prev.timeMs > gaps.stepMs ||
                x.percent > prev.percent ||
                (prev.remainingUah != null && x.remainingUah != null && x.remainingUah > prev.remainingUah + 20_000)
            if (breaks) { if (run.size >= 2) out += run; run = mutableListOf() }
            if (x.charging != true) run += x
        }
        if (run.size >= 2) out += run
        return out
    }

    /** Ordinary least squares y = a·x + b. Returns (slope, intercept, RMSE). */
    fun ols(x: List<Double>, y: List<Double>): Triple<Double, Double, Double>? {
        if (x.size < 2 || x.size != y.size) return null
        val mx = x.average(); val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val slope = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sxx
        val intercept = my - slope * mx
        val rmse = sqrt(x.indices.sumOf { val e = y[it] - (slope * x[it] + intercept); e * e } / x.size)
        return Triple(slope, intercept, rmse)
    }

    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt(); val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
