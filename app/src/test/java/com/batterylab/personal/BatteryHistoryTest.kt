package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryHistoryTest {
    private val h = 3_600_000L
    private val t = 1_000_000_000_000L
    private fun p(hours: Double, pct: Int, charging: Boolean? = false) =
        BatteryPoint(t + (hours * h).toLong(), pct, charging)

    @Test fun splitsAtChargingAndProjectsFullCharge() {
        val s = BatteryMath.dischargeStretches(listOf(
            p(0.0, 100), p(5.0, 80), p(10.0, 60),          // 4 pp/h -> 25 h from full
            p(10.5, 61, true), p(12.0, 95, true),          // charging
            p(12.5, 95), p(14.5, 85)))                     // 5 pp/h -> 20 h
        assertEquals(2, s.size)
        assertEquals(25.0, s[0].fullChargeHours, 1e-9)
        assertEquals(40, s[0].drop)
        assertEquals(20.0, s[1].fullChargeHours, 1e-9)
    }

    @Test fun unseenChargeAndLongGapEndStretches() {
        val s = BatteryMath.dischargeStretches(listOf(
            p(0.0, 90), p(2.0, 80),
            p(3.0, 95), p(5.0, 85),                        // rise > 2 pp: missed charge
            p(14.0, 70), p(16.0, 60)))                     // gap > 8 h
        assertEquals(listOf(10, 10, 10), s.map { it.drop })
    }

    @Test fun smallUpticksDoNotSplitAndShortStretchesAreDropped() {
        val s = BatteryMath.dischargeStretches(listOf(p(0.0, 80), p(1.0, 76), p(1.5, 78), p(3.0, 70)))
        assertEquals(1, s.size)
        assertEquals(10, s[0].drop)
        assertEquals(0, BatteryMath.dischargeStretches(listOf(p(0.0, 80), p(0.5, 70))).size)  // < 1 h
        assertEquals(0, BatteryMath.dischargeStretches(listOf(p(0.0, 80), p(3.0, 78))).size)  // < 4 pp
    }

    @Test fun weeklyRuntimeIsDropWeighted() {
        val week = 7 * 24 * h
        val stretches = listOf(
            DischargeStretch(t, t + 10 * h, 100, 60),               // 40 pp over 10 h
            DischargeStretch(t + h * 20, t + h * 22, 50, 40),       // 10 pp over 2 h
            DischargeStretch(t + week, t + week + 5 * h, 90, 70))   // next week
        val w = BatteryMath.weeklyRuntime(stretches) { (it - t) / week * week + t }
        assertEquals(2, w.size)
        assertEquals(2, w[0].stretches)
        assertEquals(100.0 * 12 / 50, w[0].fullChargeHours, 1e-9)    // 24 h
        assertEquals(25.0, w[1].fullChargeHours, 1e-9)
    }

    @Test fun phoneCapacityFromChargeCounter() {
        assertEquals(5000.0, BatteryMath.impliedCapacityMah(50, 2_500_000, false)!!, 1e-9)
        assertNull(BatteryMath.impliedCapacityMah(10, 500_000, false))   // below 20%
        assertNull(BatteryMath.impliedCapacityMah(50, 2_500_000, true))  // charging
        assertNull(BatteryMath.impliedCapacityMah(50, null, false))
        assertEquals(2.5, BatteryMath.median(listOf(1.0, 4.0, 2.0, 3.0))!!, 1e-9)
    }

    @Test fun timeLeftCountsDownAndReadsNaturally() {
        // 2% at 3.8%/h is about 32 minutes when fresh.
        assertEquals("30" to "min left", BatteryMath.timeLeftLabel(2 / 3.8))
        // The same reading 2 hours later: nothing left.
        assertEquals(0.0, BatteryMath.timeLeftNow(2 / 3.8, t, t + 2 * h), 1e-9)
        assertEquals("0" to "probably empty", BatteryMath.timeLeftLabel(0.0))
        assertEquals("5" to "min left", BatteryMath.timeLeftLabel(0.01))
        assertEquals("1" to "hour left", BatteryMath.timeLeftLabel(1.02))
        assertEquals("2.5" to "hours left", BatteryMath.timeLeftLabel(2.46))
        assertEquals("19" to "hours left", BatteryMath.timeLeftLabel(18.6))
        assertEquals(3.0, BatteryMath.timeLeftNow(4.0, t, t + h), 1e-9)
    }
}
