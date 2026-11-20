package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeekSplitTest {
    private val H = 3_600_000L
    private val D = 24 * H
    /** Day 0 is a Monday; days 5 and 6 of each week are the weekend. */
    private val weekend = { ms: Long -> (ms / D) % 7 in 5..6 }
    private fun s(day: Long, startHour: Long, hours: Long, drop: Int) =
        DischargeStretch(day * D + startHour * H, day * D + (startHour + hours) * H, 100, 100 - drop)

    @Test fun ratesAreTotalDropOverTotalHoursPerSide() {
        val weekdays = listOf(s(0, 8, 4, 8), s(1, 8, 4, 8), s(2, 8, 4, 12))   // 28 points over 12 h
        val weekends = listOf(s(5, 8, 4, 12), s(6, 8, 4, 12), s(12, 8, 4, 12)) // 36 points over 12 h
        val w = WeekSplitMath.of(weekdays + weekends, 0, weekend)!!
        assertEquals(28.0 / 12, w.weekdays!!.percentPerHour, 1e-9)
        assertEquals(3.0, w.weekends!!.percentPerHour, 1e-9)
        assertEquals(100.0 * (3.0 - 28.0 / 12) / (28.0 / 12), w.weekendDifferencePct!!, 1e-9)
    }

    @Test fun aStretchGoesToTheDayOfItsMidpoint() {
        // Friday 20:00 to Saturday 06:00 has its midpoint at Saturday 01:00, so it is a weekend stretch.
        val crossing = DischargeStretch(4 * D + 20 * H, 5 * D + 6 * H, 100, 80)
        val w = WeekSplitMath.of(listOf(crossing, s(6, 8, 4, 8), s(12, 8, 4, 8)), 0, weekend)!!
        assertNull(w.weekdays)
        assertEquals(3, w.weekends!!.stretches)
    }

    @Test fun aSideNeedsThreeStretchesAndTenHours() {
        val shortWeekdays = listOf(s(0, 8, 3, 6), s(1, 8, 3, 6), s(2, 8, 3, 6))  // 9 h only
        val weekends = listOf(s(5, 8, 4, 8), s(6, 8, 4, 8), s(12, 8, 4, 8))
        val w = WeekSplitMath.of(shortWeekdays + weekends, 0, weekend)!!
        assertNull(w.weekdays)
        assertNotNull(w.weekends)
        assertNull(w.weekendDifferencePct)
        assertNull(WeekSplitMath.of(weekends.take(2), 0, weekend))
    }

    @Test fun onlyStretchesEndingInTheWindowCount() {
        val old = listOf(s(0, 8, 4, 8), s(1, 8, 4, 8), s(2, 8, 4, 8))
        val recent = listOf(s(14, 8, 4, 12), s(15, 8, 4, 12), s(16, 8, 4, 12))
        assertEquals(3.0, WeekSplitMath.of(old + recent, 14 * D, weekend)!!.weekdays!!.percentPerHour, 1e-9)
    }
}
