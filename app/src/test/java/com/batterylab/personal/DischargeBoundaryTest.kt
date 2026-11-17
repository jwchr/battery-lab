package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit #4 A3: boundary cases for discharge splitting and weekly grouping. */
class DischargeBoundaryTest {
    private val m = 60_000L
    private val h = 60 * m
    private val t = 1_000_000_000_000L
    private fun p(min: Long, pct: Int, charging: Boolean? = false) = BatteryPoint(t + min * m, pct, charging)
    private fun stretches(vararg pts: BatteryPoint) = BatteryMath.dischargeStretches(pts.toList())

    @Test fun loneGlitchReadingDoesNotCreateAFakeStretch() {
        // 80 -> 5 -> 79 within half an hour is a bad read, not a 75-point drain.
        val s = stretches(p(0, 80), p(60, 79), p(75, 5), p(90, 78), p(180, 75), p(300, 70))
        assertEquals(1, s.size)
        assertEquals(80, s[0].fromPct); assertEquals(70, s[0].toPct)
        assertEquals(5.0, s[0].hours, 1e-9)
        val e = BatteryMath.estimate(listOf(p(0, 80), p(60, 79), p(75, 5), p(90, 78), p(180, 75), p(300, 70)))
        assertNotNull(e); assertEquals(2.0, e!!.percentPerHour, 1e-9)
    }

    @Test fun realSteepDropAtTheEndIsKept() {
        // A sudden final drop has no later neighbour to contradict it, so it stays.
        val s = stretches(p(0, 60), p(60, 58), p(120, 56), p(135, 30))
        assertEquals(30, s.single().toPct)
    }

    @Test fun topOffWhileChargingEndsTheStretchAndUnplugStartsANewOne() {
        val s = stretches(p(0, 90), p(120, 80), p(180, 79, true), p(240, 100, true), p(300, 100, true),
            p(360, 100), p(480, 94))
        assertEquals(2, s.size)
        assertEquals(90 to 80, s[0].fromPct to s[0].toPct)
        assertEquals(100 to 94, s[1].fromPct to s[1].toPct)
    }

    @Test fun smallUnseenRechargeDoesNotSplit() {
        // A 2-point rise is within widget rounding; the stretch runs on.
        val s = stretches(p(0, 80), p(120, 74), p(135, 76), p(300, 70))
        assertEquals(80 to 70, s.single().fromPct to s.single().toPct)
    }

    @Test fun outOfOrderInputGivesTheSameStretches() {
        val pts = listOf(p(0, 90), p(60, 88), p(120, 85), p(240, 80))
        assertEquals(BatteryMath.dischargeStretches(pts), BatteryMath.dischargeStretches(pts.reversed()))
    }

    @Test fun gapAtTheLimitStaysOneStretchAndBeyondItSplits() {
        val limit = GapLimits().stretchMs / m
        assertEquals(1, stretches(p(0, 90), p(limit, 70)).size)
        // One minute past the limit: two single-reading runs, so no stretch at all.
        assertTrue(stretches(p(0, 90), p(limit + 1, 70)).isEmpty())
    }

    @Test fun plateauCountsTowardTheStretchTime() {
        val s = stretches(p(0, 50), p(180, 50), p(240, 46)).single()
        assertEquals(4.0, s.hours, 1e-9)
        assertEquals(1.0, s.percentPerHour, 1e-9)
    }

    @Test fun stretchCrossingIntoNextWeekIsSplitByTime() {
        val week = 7 * 24 * h
        val weekOf = { ms: Long -> Math.floorDiv(ms - t, week) * week + t }
        // 10 hours, 40 points, 2.5 hours of it in the second week.
        val s = DischargeStretch(t + week - 15 * h / 2, t + week + 5 * h / 2, 100, 60)
        val w = BatteryMath.weeklyRuntime(listOf(s), weekOf)
        assertEquals(listOf(t, t + week), w.map { it.weekStartMs })
        assertEquals(listOf(1, 1), w.map { it.stretches })
        assertEquals(7.5, w[0].observedHours, 1e-9)
        assertEquals(2.5, w[1].observedHours, 1e-9)
        // Even drain assumed, so both weeks project the stretch's own 25 h.
        w.forEach { assertEquals(25.0, it.fullChargeHours, 1e-9) }
    }
}
