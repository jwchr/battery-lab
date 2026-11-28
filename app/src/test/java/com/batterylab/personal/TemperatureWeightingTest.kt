package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.P2m-i (Neon deep review, Nov 26, 2026): average temperatures count time, not polls. */
class TemperatureWeightingTest {
    private val min = 60_000L
    private val hourStep = GapLimits(stepMs = 60 * min)
    private fun r(atMin: Long, tempC: Int, charging: Boolean = false) =
        Reading("test", atMin * min, 50, charging, "test", tempC * 10)

    @Test fun sparseDeepSleepPollsCountForTheTimeTheyCover() {
        // Two awake polls 10 minutes apart at 40 C, then deep-sleep polls an hour apart at 20 C.
        // Weights (minutes): 10, 35, 60, 60, so the average is 4200 / 165, not the plain 30.
        val t = BatteryStats.temperature(listOf(r(0, 40), r(10, 40), r(70, 20), r(130, 20)), hourStep)!!
        assertEquals(4200.0 / 165, t.avgC, 1e-9)
        assertEquals(4200.0 / 165, t.avgOnBatteryC!!, 1e-9)
        assertEquals(20.0, t.minC, 1e-9)
        assertEquals(40.0, t.maxC, 1e-9)
    }

    @Test fun aLongGapCountsForOneStepAtMost() {
        // The 10-hour hole after the second poll counts as one hour: weights 10, 35, 60.
        val readings = listOf(r(0, 30), r(10, 30), r(610, 10))
        assertEquals((30.0 * 45 + 10.0 * 60) / 105, BatteryStats.temperature(readings, hourStep)!!.avgC, 1e-9)
        // With the caller's longer step the hole counts in full: weights 10, 305, 600.
        val longStep = GapLimits(stepMs = 600 * min)
        assertEquals((30.0 * 315 + 10.0 * 600) / 915, BatteryStats.temperature(readings, longStep)!!.avgC, 1e-9)
    }

    @Test fun chargingAndOnBatteryAveragesAreWeightedToo() {
        // Charging: a 10-minute poll at 36 C and an hour-long one at 30 C (weights 10, 35, 60, 60).
        val t = BatteryStats.temperature(listOf(r(0, 36, true), r(10, 36, true), r(70, 30, true), r(130, 25)), hourStep)!!
        assertEquals((36.0 * 45 + 30.0 * 60) / 105, t.avgChargingC!!, 1e-9)
        assertEquals(25.0, t.avgOnBatteryC!!, 1e-9)
    }

    @Test fun evenlySpacedPollsGiveThePlainAverageAndOrderDoesNotMatter() {
        val t = BatteryStats.temperature(listOf(r(0, 30), r(20, 34), r(10, 36)), hourStep)!!
        assertEquals(100.0 / 3, t.avgC, 1e-9)
        assertEquals(10 * min, t.maxAtMs)
    }

    @Test fun readingsAtTheSameTimeShareItsWeightInAnyOrder() {
        // Neon Nov27g P3-1: two polls at minute 0 split its 60 minutes (30 each); minute 60 gets 60.
        val expected = (40.0 * 30 + 30.0 * 30 + 20.0 * 60) / 120
        assertEquals(expected, BatteryStats.temperature(listOf(r(0, 40), r(0, 30), r(60, 20)), hourStep)!!.avgC, 1e-9)
        assertEquals(expected, BatteryStats.temperature(listOf(r(0, 30), r(0, 40), r(60, 20)), hourStep)!!.avgC, 1e-9)
        // The same at the end of the list.
        val tail = (20.0 * 60 + 40.0 * 30 + 30.0 * 30) / 120
        assertEquals(tail, BatteryStats.temperature(listOf(r(0, 20), r(60, 40), r(60, 30)), hourStep)!!.avgC, 1e-9)
        // All at one time: the plain average.
        assertEquals(35.0, BatteryStats.temperature(listOf(r(5, 40), r(5, 30)), hourStep)!!.avgC, 1e-9)
    }
}
