package com.batterylab.personal

import kotlin.math.abs
import kotlin.math.sqrt

// ================================================================================================
// Charge times — how long each device takes to charge, for the Stats page
// ================================================================================================

/** Minutes for a stretch of a charge; [measured] false when part of it was filled in from a neighbouring 10%. */
data class ChargeEstimate(val minutes: Double, val measured: Boolean, val samples: Int)

data class DeviceChargeTimes(
    val charges: Int,
    /** Median minutes for each 10% step (0→10 … 90→100) that some charge fully covered. */
    val steps: Map<Int, Double>,
    val stepSamples: Map<Int, Int>,
    val zeroToFull: ChargeEstimate?,
    val twentyToEighty: ChargeEstimate?,
    val eightyToFull: ChargeEstimate?,
    val usualPlugIn: Int?,
    val fromUsualToFull: ChargeEstimate?,
    val fastest2080: Double?,
    val slowest2080: Double?,
    /** The most recent charge: start %, end %, minutes until it first reached the end. */
    val last: Triple<Int, Int, Double>?,
)

object ChargeTimes {
    /** Lower edges of the 10% steps: 0, 10, … 90. */
    val STEPS = (0..90 step 10).toList()

    fun analyze(readings: List<Reading>, gaps: GapLimits = GapLimits()): DeviceChargeTimes? {
        val runs = ChargeCurves.chargeRuns(readings, gaps)
        if (runs.isEmpty()) return null
        val perStep = STEPS.associateWith { lo -> runs.mapNotNull { ChargeCurves.bandMinutes(it, lo, lo + 10) } }
        val steps = perStep.mapNotNull { (lo, v) -> BatteryMath.median(v)?.let { lo to it } }.toMap()
        fun direct(lo: Int, hi: Int): ChargeEstimate? {
            val v = runs.mapNotNull { ChargeCurves.bandMinutes(it, lo, hi) }
            return BatteryMath.median(v)?.let { ChargeEstimate(it, true, v.size) }
        }
        // Measured if some charge covered the whole span. Otherwise start from the longest measured
        // stretch that ends at [hi] and estimate only the part below it: adding up 10% steps alone
        // can come out shorter than a span that was actually timed.
        fun bestOf(lo: Int, hi: Int): ChargeEstimate? {
            direct(lo, hi)?.let { return it }
            for (start in STEPS.filter { it > lo && it < hi }) {
                val top = direct(start, hi) ?: continue
                val low = estimate(steps, lo, start) ?: continue
                return ChargeEstimate(low.minutes + top.minutes, low.measured, 0)
            }
            return estimate(steps, lo, hi)
        }
        val starts = runs.map { it.first().percent }
        val usual = BatteryMath.median(starts.map { it.toDouble() })?.let { Math.round(it).toInt() }
        val d2080 = runs.mapNotNull { ChargeCurves.bandMinutes(it, 20, 80) }
        val lastRun = runs.last()
        val top = lastRun.maxOf { it.percent }
        val lastMinutes = (lastRun.first { it.percent >= top }.timeMs - lastRun.first().timeMs) / 60_000.0
        return DeviceChargeTimes(runs.size, steps, perStep.mapValues { it.value.size }.filterValues { it > 0 },
            bestOf(0, 100), bestOf(20, 80), bestOf(80, 100), usual, usual?.let { bestOf(it, 100) },
            d2080.minOrNull(), d2080.maxOrNull(), Triple(lastRun.first().percent, top, lastMinutes))
    }

    /**
     * Minutes from [from]% to [to]% by adding up the median 10% steps (a partial step counts
     * pro rata). Missing steps below the lowest measured one borrow its time, since a battery
     * charges at least as fast low down; any other missing step means no estimate, because the
     * slow top of a charge can't be guessed from the fast middle.
     */
    fun estimate(steps: Map<Int, Double>, from: Int, to: Int): ChargeEstimate? {
        if (steps.isEmpty() || to <= from) return null
        val lowest = steps.keys.min()
        var total = 0.0; var measured = true
        for (lo in STEPS) {
            val overlap = (minOf(to, lo + 10) - maxOf(from, lo)).coerceAtLeast(0)
            if (overlap == 0) continue
            val t = steps[lo] ?: if (lo < lowest) { measured = false; steps.getValue(lowest) } else return null
            total += t * overlap / 10.0
        }
        return ChargeEstimate(total, measured, 0)
    }
}

// ================================================================================================
// F3 — charge bands
// ================================================================================================

/** How one observed charge moved through a standard band (for example 20→50%). */
data class BandTiming(val fromPct: Int, val toPct: Int, val minutes: Double, val watts: Double?) {
    val pctPerMinute: Double get() = (toPct - fromPct) / minutes
}

/** One charge that was actually seen charging, with its band timings and conditions. */
data class ObservedCharge(
    val startMs: Long, val endMs: Long, val fromPct: Int, val toPct: Int,
    val bands: List<BandTiming>,
    /** Most common BatteryManager.EXTRA_PLUGGED value during the charge, if recorded. */
    val pluggedType: Int?,
    val tempRiseC: Double?,
    val peakTempC: Double?,
    /** When the charge first reached [toPct]; time parked there afterwards is not charging speed. */
    val peakMs: Long = endMs,
)

object ChargeCurves {
    val BANDS = listOf(20 to 50, 50 to 80, 80 to 100)

    /** Charges built only from readings flagged charging; inferred jumps never count. */
    fun observedCharges(readings: List<Reading>, gaps: GapLimits = GapLimits()): List<ObservedCharge> =
        chargeRuns(readings, gaps).map(::build)

    /** The readings of each watched charge, in time order: a run of charging readings without gaps. */
    fun chargeRuns(readings: List<Reading>, gaps: GapLimits = GapLimits()): List<List<Reading>> {
        val r = readings.sortedBy { it.timeMs }
        val out = mutableListOf<List<Reading>>()
        var run = mutableListOf<Reading>()
        fun close() {
            if (run.size >= 2 && run.last().percent > run.first().percent) out += run
            run = mutableListOf()
        }
        for (x in r) {
            // A plugged-in hold (status NOT_CHARGING = 4, e.g. Samsung's battery protection pausing
            // at 80%) ends the timed charge: hours of holding aren't charging speed.
            if (x.charging != true || x.detail?.status == 4) { close(); continue }
            if (run.isNotEmpty() && x.timeMs - run.last().timeMs > gaps.stepMs) close()
            run += x
        }
        close()
        return out
    }

    private fun build(run: List<Reading>): ObservedCharge {
        fun firstAtOrAbove(p: Int) = run.indexOfFirst { it.percent >= p }.takeIf { it >= 0 }
        val bands = BANDS.mapNotNull { (lo, hi) ->
            val minutes = bandMinutes(run, lo, hi) ?: return@mapNotNull null
            val a = run[firstAtOrAbove(lo)!!]; val b = run[firstAtOrAbove(hi)!!]
            val ra = a.remainingUah; val rb = b.remainingUah
            val readingHours = (b.timeMs - a.timeMs) / 3_600_000.0
            val mv = run.filter { it.timeMs in a.timeMs..b.timeMs }.mapNotNull { it.voltageMv }.takeIf { it.isNotEmpty() }?.average()
            val watts = if (ra != null && rb != null && rb > ra && mv != null && readingHours > 0)
                (rb - ra) / 1000.0 * mv / 1000.0 / readingHours / 1000.0 else null
            BandTiming(lo, hi, minutes, watts)
        }
        val plugged = run.mapNotNull { it.detail?.plugged }.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val temps = run.mapNotNull { it.tempTenthsC?.div(10.0) }
        val top = run.last().percent
        return ObservedCharge(run.first().timeMs, run.last().timeMs, run.first().percent, top, bands, plugged,
            if (temps.size >= 2) temps.max() - temps.first() else null, temps.maxOrNull(),
            peakMs = run.first { it.percent >= top }.timeMs)
    }

    /**
     * Minutes one charge took from [lo]% to [hi]%, or null if it didn't cover the whole band. Each
     * crossing is interpolated between the readings either side: with 15-minute checks, timing
     * from the readings alone would snap every band to 15-minute steps.
     */
    fun bandMinutes(run: List<Reading>, lo: Int, hi: Int): Double? {
        // The band must start at or below its low edge to be timed fairly.
        if (run.isEmpty() || run.first().percent > lo) return null
        fun crossedMs(p: Int): Double? {
            val i = run.indexOfFirst { it.percent >= p }.takeIf { it >= 0 } ?: return null
            val x = run[i]; val y = run.getOrNull(i - 1) ?: return x.timeMs.toDouble()
            if (x.percent <= y.percent) return x.timeMs.toDouble()
            return y.timeMs + (p - y.percent).coerceAtLeast(0).toDouble() / (x.percent - y.percent) * (x.timeMs - y.timeMs)
        }
        val a = crossedMs(lo) ?: return null; val b = crossedMs(hi) ?: return null
        return ((b - a) / 60_000.0).takeIf { it > 0 }
    }

    /** Median minutes per band across charges, optionally for one plug type. */
    fun bandMedians(charges: List<ObservedCharge>, plugged: Int? = null): Map<Pair<Int, Int>, Double> =
        BANDS.mapNotNull { band ->
            BatteryMath.median(charges.filter { plugged == null || it.pluggedType == plugged }
                .flatMap { it.bands }.filter { it.fromPct == band.first && it.toPct == band.second }.map { it.minutes })
                ?.let { band to it }
        }.toMap()

    /**
     * Percent gained in [minutes] starting from 20%, walking the median band times in order
     * (20→50, 50→80, 80→100) so the slower top of the charge isn't priced at the fast start's speed.
     * Stops at the first band with no timing.
     */
    fun topUpPercent(charges: List<ObservedCharge>, minutes: Double = 15.0, plugged: Int? = null): Double? {
        val medians = bandMedians(charges, plugged)
        if (medians[20 to 50] == null) return null
        var left = minutes; var gained = 0.0
        for (band in BANDS) {
            val t = medians[band] ?: break
            val size = (band.second - band.first).toDouble()
            if (left >= t) { gained += size; left -= t } else { gained += size * left / t; left = 0.0; break }
        }
        return gained
    }

    fun plugName(type: Int?) = when (type) {
        1 -> "Wired (AC)"; 2 -> "USB"; 4 -> "Wireless"; 8 -> "Dock"; else -> "Unknown"
    }
}

// ================================================================================================
// F4 — idle / overnight stretches and anomalies
// ================================================================================================

data class IdleStretch(
    val startMs: Long, val endMs: Long, val fromPct: Int, val toPct: Int,
    val screenOnFraction: Double?,
    val cellularShare: Double?, val aodShare: Double?, val dozeShare: Double?, val avgTempC: Double?
) {
    val hours: Double get() = (endMs - startMs) / 3_600_000.0
    val rate: Double get() = (fromPct - toPct) / hours
}

data class IdleReport(
    val stretches: List<IdleStretch>,
    val medianRate: Double?,
    val mad: Double?,
    val unusual: List<IdleStretch>
)

object IdleAnalysis {
    /**
     * Uninterrupted screen-off discharges of at least [minHours]. Screen state comes from
     * [screenOn] spans when given, otherwise from recorded display state. A stretch's end is pulled
     * back to the last reading that showed a change, so a cached widget value repeating with no
     * fresh data can't make a stretch look flat.
     */
    fun stretches(readings: List<Reading>, screenOn: List<Span>? = null, minHours: Double = 4.0,
                  gaps: GapLimits = GapLimits(), screenCoverageStartMs: Long? = null): List<IdleStretch> {
        val idx = screenOn?.let { SpanIndex(it) }
        fun covered(ms: Long) = idx != null && (screenCoverageStartMs == null || ms >= screenCoverageStartMs)
        // A run from unplug to plug-in usually holds both a busy day and a quiet night. Cut it at
        // every step that saw the screen on, so the quiet stretches inside are measured on their
        // own instead of the whole run being thrown out.
        fun busy(a: Reading, b: Reading): Boolean =
            if (covered(a.timeMs)) idx!!.overlap(a.timeMs, b.timeMs) > 0.05 * (b.timeMs - a.timeMs)
            else a.detail?.displayState == 2 || b.detail?.displayState == 2
        val quiet = HonestyAuditor.dischargeRuns(readings.sortedBy { it.timeMs }, gaps).flatMap { run ->
            val parts = mutableListOf<List<Reading>>()
            var start = 0
            for (i in 0 until run.size - 1) if (busy(run[i], run[i + 1])) {
                if (i > start) parts += run.subList(start, i + 1)
                start = i + 1
            }
            if (run.size - 1 > start) parts += run.subList(start, run.size)
            parts
        }
        return quiet.mapNotNull { run0 ->
            var end = run0.size - 1
            // Only the widget repeats a cached value; a repeated phone reading is real flat time.
            while (end > 0 && run0[end].source == SamsungWidgetSource.SOURCE &&
                QualityFlags.has(run0[end].detail?.qualityFlags, QualityFlags.UNCHANGED_REPEAT) &&
                run0[end].percent == run0[end - 1].percent) end--
            val run = run0.subList(0, end + 1)
            if (run.size < 2) return@mapNotNull null
            val a = run.first(); val b = run.last()
            val hours = (b.timeMs - a.timeMs) / 3_600_000.0
            if (hours < minHours) return@mapNotNull null
            // Android keeps about a week of screen history; before that, no spans means unknown,
            // not "screen off", so older stretches fall back to the recorded display state.
            val onFraction = when {
                covered(a.timeMs) -> idx!!.overlap(a.timeMs, b.timeMs).toDouble() / (b.timeMs - a.timeMs)
                else -> run.mapNotNull { it.detail?.displayState }.takeIf { it.size >= run.size / 2 }
                    ?.let { s -> s.count { it == 2 }.toDouble() / s.size }
            }
            if (onFraction == null || onFraction > 0.05) return@mapNotNull null
            fun share(pred: (ReadingDetail) -> Boolean?): Double? =
                run.mapNotNull { r -> r.detail?.let(pred) }.takeIf { it.isNotEmpty() }?.let { v -> v.count { it }.toDouble() / v.size }
            IdleStretch(a.timeMs, b.timeMs, a.percent, b.percent, onFraction,
                share { d -> d.transport?.let { it.startsWith("cellular") } },
                share { d -> d.displayState?.let { it == 3 || it == 4 } },
                share { d -> d.deviceIdle },
                run.mapNotNull { it.tempTenthsC?.div(10.0) }.takeIf { it.isNotEmpty() }?.average())
        }
    }

    /** Flags stretches draining faster than median + 3 MAD (and at least 0.5 %/h above it). */
    fun report(stretches: List<IdleStretch>): IdleReport {
        if (stretches.size < 5) return IdleReport(stretches, BatteryMath.median(stretches.map { it.rate }), null, emptyList())
        val rates = stretches.map { it.rate }
        val m = BatteryMath.median(rates)!!
        val mad = BatteryMath.median(rates.map { abs(it - m) })!!
        val unusual = stretches.filter { it.rate > m + 3 * mad && it.rate >= m + 0.5 }
        return IdleReport(stretches, m, mad, unusual)
    }
}

// ================================================================================================
// F7 — heat × charge exposure
// ================================================================================================

object HeatExposure {
    val TEMP_BANDS = listOf("<30°C", "30–38°C", ">38°C")
    val CHARGE_BANDS = listOf("<20%", "20–80%", "80–100%")

    /** Hours observed in each [temperature band][charge band] cell, time-weighted by step. */
    fun matrix(readings: List<Reading>, gaps: GapLimits = GapLimits()): Array<DoubleArray> {
        val m = Array(3) { DoubleArray(3) }
        for ((a, b) in HonestyAuditor.steps(readings, gaps)) {
            val t = a.tempTenthsC ?: continue
            val ti = when { t < 300 -> 0; t <= 380 -> 1; else -> 2 }
            val ci = when { a.percent < 20 -> 0; a.percent <= 80 -> 1; else -> 2 }
            m[ti][ci] += (b.timeMs - a.timeMs) / 3_600_000.0
        }
        return m
    }
}

// ================================================================================================
// F5 — readiness planner
// ================================================================================================

data class DeviceReadiness(
    val name: String,
    val percent: Int,
    /** Hours until [reservePct] at the typical (median) rate, and at the 25th/75th percentile rates. */
    val hoursToReserve: Double?,
    val hoursToReserveFast: Double?,
    val hoursToReserveSlow: Double?,
    val meetsTarget: Boolean?,
    /** Minutes on the charger needed to last the window, from learned charge speed. */
    val topUpMinutes: Double?,
    /** Hours from a full charge down to the reserve at the typical rate. */
    val hoursFromFull: Double? = null
)

object ReadinessPlanner {
    /**
     * Per-device survival over [targetHours] keeping [reservePct]. Devices are never averaged
     * together; [drainRates] are each device's historical stretch rates (%/h) and [chargeRate]
     * its median charge speed (%/min).
     */
    fun assess(name: String, percent: Int, drainRates: List<Double>, chargeRate: Double?,
               targetHours: Double, reservePct: Int): DeviceReadiness {
        val sorted = drainRates.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return DeviceReadiness(name, percent, null, null, null, null, null)
        val typical = HonestyAuditor.quantile(sorted, 0.5)
        val fast = HonestyAuditor.quantile(sorted, 0.75)
        val slow = HonestyAuditor.quantile(sorted, 0.25)
        val usable = (percent - reservePct).coerceAtLeast(0)
        fun h(rate: Double) = usable / rate
        val needed = (typical * targetHours + reservePct - percent).coerceAtLeast(0.0)
        val minutes = if (needed <= 0) 0.0 else chargeRate?.takeIf { it > 0 }?.let { minOf(needed, 100.0 - percent) / it }
        return DeviceReadiness(name, percent, h(typical), h(fast), h(slow), h(typical) >= targetHours, minutes,
            (100 - reservePct) / typical)
    }

    /** Charging order with one charger: the device that falls short soonest goes first. */
    fun order(devices: List<DeviceReadiness>): List<DeviceReadiness> =
        devices.filter { it.meetsTarget == false }.sortedBy { it.hoursToReserve ?: Double.MAX_VALUE }
}

// ================================================================================================
// F2 — forecast scoring
// ================================================================================================

data class ForecastScore(val actual2h: Double?, val actual4h: Double?, val actual8h: Double?, val status: String)

data class HorizonStats(val hours: Int, val n: Int, val labMae: Double?, val labBias: Double?,
                        val androidN: Int, val androidMae: Double?, val androidBias: Double?)

object ForecastScoring {
    const val MODEL_VERSION = "estimate-1"
    val HORIZONS = listOf(2, 4, 8)

    /**
     * The percentage actually seen at issue + 2, 4 and 8 hours, interpolated between the two
     * readings around each horizon (each within 30 min of it). Only readings after the forecast
     * are used, so nothing it couldn't have known leaks in. A charge (or a rise over 2 points)
     * before a horizon censors that horizon and the later ones; a horizon with no nearby readings
     * is censored as a gap. Stays PENDING until 8 h have passed (or everything is censored).
     */
    fun score(f: Forecast, readingsAfter: List<Reading>, nowMs: Long): ForecastScore {
        val r = readingsAfter.filter { it.timeMs >= f.issuedMs }.sortedBy { it.timeMs }
        val actual = arrayOfNulls<Double>(3)
        var charged = false; var gap = false
        for ((i, h) in HORIZONS.withIndex()) {
            val target = f.issuedMs + h * 3_600_000L
            if (nowMs < target + 30 * 60_000L) return ForecastScore(actual[0], actual[1], actual[2], "PENDING")
            val before = r.filter { it.timeMs <= target }
            if (before.any { it.charging == true } || rose(before, f.startPct)) { charged = true; break }
            val prev = before.lastOrNull { target - it.timeMs <= 30 * 60_000L }
            // The first reading at or after the horizon, and only if no charge came before it:
            // skipping a charging reading would score the forecast against a topped-up value.
            val after = r.firstOrNull { it.timeMs >= target }?.takeIf { it.timeMs - target <= 30 * 60_000L }
            val next = after?.takeIf { it.charging != true && (prev == null || it.percent <= prev.percent + 2) }
            // A charge right after the horizon censors it as charged, not as a gap.
            if (after != null && next == null) { charged = true; break }
            actual[i] = when {
                prev != null && next != null && next.timeMs > prev.timeMs ->
                    prev.percent + (next.percent - prev.percent) * (target - prev.timeMs).toDouble() / (next.timeMs - prev.timeMs)
                prev != null && prev.timeMs == target -> prev.percent.toDouble()
                next != null && next.timeMs == target -> next.percent.toDouble()
                else -> { gap = true; null }
            }
        }
        val any = actual.any { it != null }
        val status = when {
            any -> "SCORED"
            charged -> "CENSORED_CHARGED"
            gap -> "CENSORED_GAP"
            else -> "CENSORED_GAP"
        }
        return ForecastScore(actual[0], actual[1], actual[2], status)
    }

    /** A rescore never erases a horizon already saved; once any horizon is scored, a final status is SCORED. */
    fun keepScored(f: Forecast, s: ForecastScore): ForecastScore {
        val a2 = f.actual2h ?: s.actual2h; val a4 = f.actual4h ?: s.actual4h; val a8 = f.actual8h ?: s.actual8h
        val status = if (s.status != "PENDING" && (a2 != null || a4 != null || a8 != null)) "SCORED" else s.status
        return ForecastScore(a2, a4, a8, status)
    }

    /** True if the percentage ever climbs more than 2 points above its lowest value so far. */
    private fun rose(r: List<Reading>, startPct: Int): Boolean {
        var low = startPct
        for (x in r) { if (x.percent > low + 2) return true; low = minOf(low, x.percent) }
        return false
    }

    /** Mean absolute error and signed bias (predicted − actual; positive = optimistic) per horizon. */
    fun reportCard(forecasts: List<Forecast>): List<HorizonStats> = HORIZONS.mapIndexed { i, h ->
        fun actualOf(f: Forecast) = when (i) { 0 -> f.actual2h; 1 -> f.actual4h; else -> f.actual8h }
        val scored = forecasts.filter { actualOf(it) != null }
        val labErr = scored.map { it.labPredicted(h.toDouble()).coerceAtLeast(0.0) - actualOf(it)!! }
        val andErr = scored.mapNotNull { f -> f.androidPredicted(h.toDouble())?.coerceAtLeast(0.0)?.let { it - actualOf(f)!! } }
        HorizonStats(h, labErr.size,
            labErr.takeIf { it.isNotEmpty() }?.map { abs(it) }?.average(), labErr.takeIf { it.isNotEmpty() }?.average(),
            andErr.size, andErr.takeIf { it.isNotEmpty() }?.map { abs(it) }?.average(), andErr.takeIf { it.isNotEmpty() }?.average())
    }
}

// ================================================================================================
// F6 — experiment statistics
// ================================================================================================

data class ExperimentResult(
    val experiment: Experiment,
    /** Drain rate (%/h) from the start and end readings alone. */
    val endpointRate: Double?,
    /** Drain rate from a least-squares fit over genuine percentage changes, when there are enough. */
    val regressionRate: Double?,
    val transitions: Int,
    /** Why the run can't be trusted, or null. */
    val quarantine: String?
) {
    val rate: Double? get() = if (quarantine != null) null else regressionRate ?: endpointRate
}

data class Interval(val estimate: Double, val low: Double, val high: Double, val n: Int)

data class ExperimentComparison(
    val armA: String, val armB: String,
    val meanA: Double?, val meanB: Double?, val nA: Int, val nB: Int,
    /** B − A drain-rate difference over runs done in chronological pairs. */
    val paired: Interval?,
    /** B − A difference treating runs as independent (Welch). */
    val welch: Interval?
) {
    /** Implied runtime change of B relative to A (+30% means B lasts 30% longer). */
    val runtimeChange: Double? get() = if (meanA != null && meanB != null && meanB > 0) meanA / meanB - 1 else null

    /**
     * The comparison to report. Paired only when both arms have the same number of runs: with
     * unequal counts, pairing leaves the extra runs out. Otherwise Welch, which uses every run.
     */
    val chosen: Interval? get() = if (nA == nB) paired ?: welch else welch

    /** Headline B − A difference, from the same comparison as [chosen]'s range. */
    val difference: Double? get() = chosen?.estimate ?: if (meanA != null && meanB != null) meanB - meanA else null
}

object ExperimentStats {
    fun result(e: Experiment, readings: List<Reading>): ExperimentResult {
        val end = e.endMs
        val endpoint = BatteryMath.experimentDrain(e.startMs, e.endMs, e.startPct, e.endPct)
        if (end == null) return ExperimentResult(e, null, null, 0, "Still running")
        // One Ring source per arm: widget and Bluetooth values alternate in the Ring's history and
        // can differ by a few points, which read as rises and extra changes.
        val inRun = readings.filter { it.timeMs in e.startMs..end && usesSource(e.mode, it.source) }.sortedBy { it.timeMs }
        if (inRun.any { it.charging == true }) return ExperimentResult(e, endpoint, null, 0, "Charged during the run")
        if (inRun.zipWithNext().any { (a, b) -> b.percent > a.percent + 2 }) return ExperimentResult(e, endpoint, null, 0, "Percentage rose mid-run")
        // Only real changes count: a cached value repeating every check is one observation, not many.
        val changes = inRun.filterIndexed { i, r -> i == 0 || r.percent != inRun[i - 1].percent }
        val pts = (listOf(BatteryPoint(e.startMs, e.startPct, false)) + changes.map { BatteryPoint.of(it) } +
            listOf(BatteryPoint(end, e.endPct ?: inRun.lastOrNull()?.percent ?: e.startPct, false))).distinctBy { it.timeMs }
        val fit = if (pts.map { it.percent }.distinct().size >= 4)
            HonestyAuditor.ols(pts.map { (it.timeMs - e.startMs) / 3_600_000.0 }, pts.map { it.percent.toDouble() }) else null
        return ExperimentResult(e, endpoint, fit?.first?.let { -it }?.takeIf { it >= 0 }, changes.size, null)
    }

    private val WIDGET_SOURCES = setOf(SamsungWidgetSource.SOURCE, "manual_widget")

    /**
     * Whether a reading from [source] belongs to an arm's series: the monitoring arm reads the
     * Ring over Bluetooth, the other arms through the widget (or a manual entry of it). Other
     * sources are left alone.
     */
    fun usesSource(mode: String, source: String): Boolean =
        if (ExperimentEndpoint.allowsBle(mode)) source !in WIDGET_SOURCES else source != "ring_ble"

    /**
     * Compares arm [b] against arm [a]. Runs are paired in time order (the i-th of each arm); with
     * at least 2 pairs a paired-difference 95% interval is given. Welch's interval needs 2+ runs
     * per arm.
     */
    fun compare(results: List<ExperimentResult>, a: String, b: String): ExperimentComparison {
        val ra = results.filter { it.experiment.mode == a }.sortedBy { it.experiment.startMs }.mapNotNull { it.rate }
        val rb = results.filter { it.experiment.mode == b }.sortedBy { it.experiment.startMs }.mapNotNull { it.rate }
        val pairs = minOf(ra.size, rb.size)
        val paired = if (pairs >= 2) {
            val d = (0 until pairs).map { rb[it] - ra[it] }
            val mean = d.average(); val sd = sd(d)
            val half = tCrit(pairs - 1.0) * sd / sqrt(pairs.toDouble())
            Interval(mean, mean - half, mean + half, pairs)
        } else null
        val welch = if (ra.size >= 2 && rb.size >= 2) {
            val va = sd(ra).let { it * it } / ra.size; val vb = sd(rb).let { it * it } / rb.size
            val diff = rb.average() - ra.average()
            val df = if (va + vb == 0.0) (ra.size + rb.size - 2.0)
                else (va + vb) * (va + vb) / (va * va / (ra.size - 1) + vb * vb / (rb.size - 1))
            val half = tCrit(df) * sqrt(va + vb)
            Interval(diff, diff - half, diff + half, ra.size + rb.size)
        } else null
        return ExperimentComparison(a, b, ra.takeIf { it.isNotEmpty() }?.average(), rb.takeIf { it.isNotEmpty() }?.average(),
            ra.size, rb.size, paired, welch)
    }

    private fun sd(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    /** Two-sided 95% Student-t critical value (interpolated table; normal beyond 120 df). */
    fun tCrit(df: Double): Double {
        val table = listOf(1.0 to 12.706, 2.0 to 4.303, 3.0 to 3.182, 4.0 to 2.776, 5.0 to 2.571, 6.0 to 2.447,
            7.0 to 2.365, 8.0 to 2.306, 9.0 to 2.262, 10.0 to 2.228, 12.0 to 2.179, 15.0 to 2.131, 20.0 to 2.086,
            25.0 to 2.060, 30.0 to 2.042, 40.0 to 2.021, 60.0 to 2.000, 120.0 to 1.980)
        if (df <= 1.0) return table.first().second
        if (df >= 120.0) return 1.96
        val hi = table.indexOfFirst { it.first >= df }
        val (x0, y0) = table[hi - 1]; val (x1, y1) = table[hi]
        return y0 + (y1 - y0) * (df - x0) / (x1 - x0)
    }
}

// ================================================================================================
// F1 — before/after a change
// ================================================================================================

data class UpdateImpact(
    val entry: JournalEntry,
    val before: List<IdleStretch>,
    val after: List<IdleStretch>,
    val delta: Interval?,
    /** "Preliminary" with 3–6 sessions per side, "Established" with 7+, else null. */
    val strength: String?,
    val confounder: String?
)

object UpdateImpactAnalysis {
    /** Only phone changes can be judged: the idle stretches come from the phone's own readings. */
    fun measures(e: JournalEntry) = e.device == "phone"

    /** Hours after an OS update treated as background re-indexing and left out. */
    const val SETTLE_HOURS = 6

    fun assess(entry: JournalEntry, stretches: List<IdleStretch>): UpdateImpact {
        val changeStart = entry.windowStartMs ?: entry.timeMs
        val settle = if (entry.category == "os_update") SETTLE_HOURS * 3_600_000L else 0L
        val before = stretches.filter { it.endMs <= changeStart }.takeLast(14)
        val after = stretches.filter { it.startMs >= entry.timeMs + settle }.take(14)
        val strength = when {
            before.size >= 7 && after.size >= 7 -> "Established"
            before.size >= 3 && after.size >= 3 -> "Preliminary"
            else -> null
        }
        val delta = if (strength == null) null else {
            val fake = before.map { ExperimentResult(Experiment(0, "", "before", it.startMs, it.fromPct, it.endMs, it.toPct), it.rate, null, 0, null) } +
                after.map { ExperimentResult(Experiment(0, "", "after", it.startMs, it.fromPct, it.endMs, it.toPct), it.rate, null, 0, null) }
            ExperimentStats.compare(fake, "before", "after").welch
        }
        fun mean(list: List<IdleStretch>, f: (IdleStretch) -> Double?) = list.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        val cellB = mean(before) { it.cellularShare }; val cellA = mean(after) { it.cellularShare }
        val aodB = mean(before) { it.aodShare }; val aodA = mean(after) { it.aodShare }
        val confounder = when {
            cellB != null && cellA != null && abs(cellA - cellB) >= 0.25 ->
                "Confounded: cellular time went from ${(cellB * 100).toInt()}% to ${(cellA * 100).toInt()}%"
            aodB != null && aodA != null && abs(aodA - aodB) >= 0.25 ->
                "Confounded: Always-On Display time went from ${(aodB * 100).toInt()}% to ${(aodA * 100).toInt()}%"
            else -> null
        }
        return UpdateImpact(entry, before, after, delta, strength, confounder)
    }
}
