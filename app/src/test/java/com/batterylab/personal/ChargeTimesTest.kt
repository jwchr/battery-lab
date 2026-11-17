package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeTimesTest {
    private val M = 60_000L
    private fun ring(min: Long, pct: Int, charging: Boolean = true) =
        Reading("ring", min * M, pct, charging, SamsungWidgetSource.SOURCE)

    // Charges at a steady 10% per 6 minutes from 10%, read every 15 minutes: 10, 35, 60, 85, 100.
    private val steady = listOf(ring(0, 10), ring(15, 35), ring(30, 60), ring(45, 85), ring(54, 100), ring(69, 100, false))

    @Test fun stepsAreInterpolatedBetweenReadings() {
        val t = ChargeTimes.analyze(steady)!!
        for (lo in 10..90 step 10) assertEquals("step $lo", 6.0, t.steps.getValue(lo), 1e-9)
        assertNull(t.steps[0])
        assertEquals(1, t.charges)
    }

    @Test fun twentyToEightyIsMeasuredDirectly() {
        val e = ChargeTimes.analyze(steady)!!.twentyToEighty!!
        assertEquals(36.0, e.minutes, 1e-9)
        assertEquals(1, e.samples)
    }

    @Test fun zeroToFullBorrowsTheLowestStepAndSaysSo() {
        val e = ChargeTimes.analyze(steady)!!.zeroToFull!!
        assertEquals(60.0, e.minutes, 1e-9)
        assertFalse(e.measured)
    }

    @Test fun lastChargeStopsTheClockAtTheTop() {
        val (from, to, minutes) = ChargeTimes.analyze(steady)!!.last!!
        assertEquals(10, from); assertEquals(100, to); assertEquals(54.0, minutes, 1e-9)
    }

    @Test fun missingTopStepGivesNoEstimate() {
        val steps = (0..80 step 10).associateWith { 5.0 }   // 90→100 never seen
        assertNull(ChargeTimes.estimate(steps, 0, 100))
        val partial = ChargeTimes.estimate(steps, 25, 80)!!
        assertEquals(27.5, partial.minutes, 1e-9)
        assertTrue(partial.measured)
    }

    @Test fun noChargesMeansNothingToShow() {
        assertNull(ChargeTimes.analyze(listOf(ring(0, 80, false), ring(15, 79, false))))
    }
}
