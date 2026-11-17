package com.batterylab.personal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Audit #4 A6: the Stats page runs this math over every stored reading. This times it on a
 * synthetic year of 15-minute readings per device and fails on a blow-up (e.g. quadratic loops),
 * not on ordinary machine-speed noise. The budget is for the JVM on a dev Mac; phones are slower.
 */
class StatsPerformanceTest {
    private val step = 15 * 60_000L
    private val t0 = 1_700_000_000_000L

    /** Sawtooth: drains [drainPerStep] per poll down to 20%, then charges to 100% over ~1.5 h. */
    private fun year(device: String, drainPerStep: Double, phone: Boolean): List<Reading> {
        val out = ArrayList<Reading>()
        var pct = 100.0
        var charging = false
        var t = t0
        val end = t0 + 365L * 86_400_000L
        while (t < end) {
            if (charging) { pct += 15.0; if (pct >= 100.0) { pct = 100.0; charging = false } }
            else { pct -= drainPerStep; if (pct <= 20.0) charging = true }
            val p = pct.toInt().coerceIn(0, 100)
            out += if (phone) Reading(device, t, p, charging, "android", tempTenthsC = 300 + p / 10,
                voltageMv = 3600 + p * 6, remainingUah = p * 50_000)
            else Reading(device, t, p, charging, "samsung_widget")
            t += step
        }
        return out
    }

    private inline fun <T> time(name: String, times: MutableList<Pair<String, Long>>, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        times += name to (System.nanoTime() - start) / 1_000_000
        return result
    }

    @Test fun oneYearOfHistoryStaysWithinBudget() {
        val gaps = GapLimits.forInterval(15)
        val hourOf = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY) }
        val raw = listOf(year("phone", 1.2, true), year("watch:w", 0.9, false), year("ring:r", 0.4, false))
        // Warm up the JIT once so the timing reflects steady state, as on a second page open.
        raw.forEach { BatteryStats.device(BatteryStats.fullHoldAsCharging(it), hourOf, gaps) }

        val times = mutableListOf<Pair<String, Long>>()
        val data = time("fullHoldAsCharging x3", times) { raw.map { BatteryStats.fullHoldAsCharging(it) } }
        time("BatteryStats.device x3", times) { data.map { BatteryStats.device(it, hourOf, gaps) } }
        time("estimatePhoneCapacity", times) { HonestyAuditor.estimatePhoneCapacity(data[0], gaps) }
        time("IdleAnalysis.stretches", times) { IdleAnalysis.stretches(data[0], null, gaps = gaps) }
        time("observedCharges", times) { ChargeCurves.observedCharges(data[0], gaps) }
        time("dischargeStretches x3", times) {
            data.map { d -> BatteryMath.dischargeStretches(d.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = gaps) }
        }
        time("temperature", times) { BatteryStats.temperature(data[0]) }

        val total = times.sumOf { it.second }
        println("A6 benchmark, ${raw.sumOf { it.size }} readings:")
        times.forEach { (n, ms) -> println("  %-26s %5d ms".format(n, ms)) }
        println("  %-26s %5d ms".format("total", total))
        assertTrue("stats math took $total ms for a year of history", total < 3_000)
    }
}
