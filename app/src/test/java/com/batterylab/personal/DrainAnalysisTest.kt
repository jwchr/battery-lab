package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrainAnalysisTest {
    private val h = 3_600_000L
    private val t = 1_000_000_000_000L
    private fun p(hours: Double, pct: Int, charging: Boolean? = false) = BatteryPoint(t + (hours * h).toLong(), pct, charging)
    private fun span(from: Double, to: Double) = Span(t + (from * h).toLong(), t + (to * h).toLong())

    @Test fun splitsIdleFromAppsByScreenTime() {
        // Hours 0-2 screen off: 1%/h idle. Hours 2-3 screen on: 11% drop = 1 idle + 10 apps.
        val points = listOf(p(0.0, 100), p(1.0, 99), p(2.0, 98), p(2.5, 92), p(3.0, 87))
        val screen = listOf(span(2.0, 3.0))
        val apps = mapOf("chrome" to listOf(span(2.0, 2.75)), "maps" to listOf(span(2.75, 3.0)))
        val d = DrainAnalysis.analyze(points, screen, apps, h)
        assertEquals(13.0, d.totalDrop, 1e-9)
        assertEquals(1.0, d.screenOffRate!!, 1e-9)
        assertEquals(3.0, d.standbyDrop, 1e-9)
        assertEquals(11.0, d.screenOnRate!!, 1e-9)
        // Step 2-2.5: 6 drop, 0.5 idle -> 5.5 chrome. Step 2.5-3: 5 drop, 0.5 idle -> 4.5 split 1:1.
        assertEquals(listOf("chrome", "maps"), d.apps.map { it.app })
        assertEquals(7.75, d.apps[0].percent, 1e-9)
        assertEquals(2.25, d.apps[1].percent, 1e-9)
        assertEquals(0.75, d.apps[0].foregroundHours, 1e-9)
    }

    @Test fun ignoresChargingAndGapsAndSplitsNothingWithoutABaseline() {
        val points = listOf(p(0.0, 50), p(1.0, 60, true), p(2.0, 60), p(5.0, 50), p(5.5, 45))
        val d = DrainAnalysis.analyze(points, listOf(span(5.0, 5.5)), emptyMap(), h)
        // Only the last step counts (the 3 h step exceeds the 1 h limit); no idle rate yet.
        assertEquals(5.0, d.totalDrop, 1e-9)
        assertNull(d.screenOffRate)
        assertEquals(false, d.baselineKnown)
        assertEquals(0, d.apps.size)
    }

    @Test fun unknownIdleIsNotZeroIdle() {
        // Review case: 100 -> 90 in 30 min, no screen data. Must not claim 100% used by apps.
        val d = DrainAnalysis.analyze(listOf(p(0.0, 100), p(0.5, 90)), emptyList(), emptyMap(), h)
        assertEquals(false, d.baselineKnown)
        assertEquals(0, d.apps.size)
        assertNull(d.screenOnRate)
    }

    @Test fun neverReportsScreenOnDrainBelowIdle() {
        // Review case: 100, 96, 96 with the screen on only for the last half hour gave -4 %/h.
        val d = DrainAnalysis.analyze(listOf(p(0.0, 100), p(1.0, 96), p(2.0, 96)), listOf(span(1.5, 2.0)), emptyMap(), h)
        assertEquals(4.0, d.screenOffRate!!, 1e-9)
        assertNull(d.screenOnRate)
    }

    @Test fun gapLimitsGrowWithLongerIntervals() {
        assertEquals(GapLimits(), GapLimits.forInterval(15))
        assertEquals(GapLimits(stepMs = 150 * 60_000L), GapLimits.forInterval(60))
        assertEquals(10 * h, GapLimits.forInterval(240).stepMs)
        assertEquals(12 * h, GapLimits.forInterval(240).stretchMs)
        // At a 4 h interval, readings 4 h apart still form one stretch.
        val s = BatteryMath.dischargeStretches(listOf(p(0.0, 100), p(4.0, 80), p(8.0, 60)),
            gaps = GapLimits.forInterval(240))
        assertEquals(1, s.size)
    }
}
