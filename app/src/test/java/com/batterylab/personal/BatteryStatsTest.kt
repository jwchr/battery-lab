package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryStatsTest {
    private val m = 60_000L
    private val t0 = 1_000_000_000_000L
    private fun r(min: Long, pct: Int, charging: Boolean? = false, temp: Int? = null) =
        Reading("phone", t0 + min * m, pct, charging, "android", temp)
    private val hourOf = { ms: Long -> (((ms - t0) / (60 * m)) % 24).toInt() }

    @Test fun countsUsageChargesAndTime() {
        val s = BatteryStats.device(listOf(
            r(0, 50), r(30, 45), r(60, 40),                   // used 10 on battery
            r(75, 41, true), r(105, 80, true), r(120, 90, true),  // watched charge 40 -> 90
            r(135, 90), r(165, 85),                            // used 5
            r(180, 96)                                          // inferred charge 85 -> 96
        ), hourOf)
        assertEquals(15, s.percentUsed)
        assertEquals(2, s.charges.size)
        assertEquals(40, s.charges[0].fromPct)
        assertEquals(90, s.charges[0].toPct)
        assertEquals(50.0 / 60.0, s.charges[0].ratePerMinute!!, 1e-9)   // 50 points from the last unplugged reading, 60 min
        assertEquals(85, s.charges[1].fromPct)
        assertEquals((40 + 85) / 2.0, s.averagePlugIn!!, 1e-9)
        assertEquals(40 to (t0 + 60 * m), s.lowest)
        assertEquals(1.0, s.hoursCharging, 1e-9)    // charging from minute 75 to 135
    }

    @Test fun gapsAreNotCountedAsTime() {
        val s = BatteryStats.device(listOf(r(0, 15), r(30, 12), r(600, 10)), hourOf)
        assertEquals(0.5, s.hoursBelow20, 1e-9)     // the 9.5-hour gap is skipped
        assertEquals(3, s.percentUsed)               // only the 30-minute step counts; the gap is skipped
    }

    @Test fun drainByHourAndLongestStretch() {
        val s = BatteryStats.device(listOf(r(0, 100), r(60, 90), r(120, 88), r(180, 87)), hourOf)
        assertEquals(10.0, s.drainByHour[0]!!, 1e-9)
        assertEquals(2.0, s.drainByHour[1]!!, 1e-9)
        assertEquals(0, s.hungriestHour)
        assertEquals(2, s.calmestHour)
        assertEquals(3.0, s.longestOnBatteryHours, 1e-9)
        assertNull(s.drainByHour[5])
    }

    @Test fun temperatureAndEnergy() {
        val t = BatteryStats.temperature(listOf(r(0, 50, false, 300), r(10, 60, true, 360), r(20, 70, true, 340)))!!
        assertEquals(36.0, t.maxC, 1e-9)
        assertEquals(35.0, t.avgChargingC!!, 1e-9)
        assertEquals(30.0, t.avgOnBatteryC!!, 1e-9)
        assertEquals(19.35, BatteryStats.wattHours(100, 5000.0), 1e-9)
    }
}
