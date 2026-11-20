package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighChargeTest {
    private val m = 60_000L
    private fun r(min: Long, pct: Int, charging: Boolean? = false) = Reading("phone", min * m, pct, charging, "android")
    private val hourOf = { ms: Long -> ((ms / (60 * m)) % 24).toInt() }

    @Test fun timeAtOrAbove80CountsPluggedInAndNot() {
        val s = BatteryStats.device(listOf(r(0, 75), r(30, 82), r(60, 88, true), r(90, 100, true),
            r(120, 100), r(150, 95), r(180, 79), r(200, 70)), hourOf)
        // Steps starting at 82, 88, 100, 100 and 95 count; the one starting at 79 doesn't.
        assertEquals(2.5, s.hoursAtOrAbove80, 1e-9)
    }

    @Test fun exactly80CountsAndGapsDoNot() {
        val s = BatteryStats.device(listOf(r(0, 80), r(30, 79), r(40, 85), r(400, 84)), hourOf)
        assertEquals(0.5, s.hoursAtOrAbove80, 1e-9)
    }

    @Test fun partialShareCountsWatchedChargesUnpluggedBelow100() {
        val s = BatteryStats.device(listOf(r(0, 50), r(30, 60, true), r(60, 90, true), r(90, 88),
            r(120, 80, true), r(150, 100, true), r(180, 99)), hourOf)
        assertEquals(2, s.charges.count { it.observed })
        assertEquals(0.5, s.partialChargeShare!!, 1e-9)
    }

    @Test fun inferredChargesAreLeftOutOfThePartialShare() {
        // 75 -> 82 is an unseen charge; the watched one reaches 100.
        val s = BatteryStats.device(listOf(r(0, 75), r(30, 82), r(60, 88, true), r(90, 100, true), r(120, 100)), hourOf)
        assertEquals(0.0, s.partialChargeShare!!, 1e-9)
        assertNull(BatteryStats.device(listOf(r(0, 50), r(30, 45)), hourOf).partialChargeShare)
    }

    @Test fun aChargeStillRunningIsNotPartial() {
        val s = BatteryStats.device(listOf(r(0, 50), r(30, 60, true), r(60, 90, true)), hourOf)
        assertEquals(1, s.charges.size)
        assertEquals(false, s.charges[0].ended)
        assertNull(s.partialChargeShare)
    }

    @Test fun aChargeCutOffByAGapIsNotPartial() {
        // Charging at 70%, then nothing for 6 hours, then charging again: the first charge's end is unknown.
        val s = BatteryStats.device(listOf(r(0, 50), r(30, 70, true), r(400, 40, true), r(430, 60, true), r(460, 60)), hourOf)
        assertEquals(listOf(false, true), s.charges.map { it.ended })
        assertEquals(1.0, s.partialChargeShare!!, 1e-9)
    }
}
