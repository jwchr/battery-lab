package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the 2026-11-16 Stats math review. */
class StatsReviewFixesTest {
    private val m = 60_000L
    private val h = 60 * m
    private val t = 1_000_000_000_000L
    private val hourOf = { ms: Long -> (((ms - t) / h) % 24).toInt() }
    private fun r(min: Long, pct: Int, charging: Boolean? = false) = Reading("phone", t + min * m, pct, charging, "android")
    private fun p(min: Long, pct: Int) = BatteryPoint(t + min * m, pct, false)

    @Test fun screenOffOneStepDropsAreAllIdleNotApps() {
        // 8 h screen off, 15-min checks, one 1-point drop every hour.
        val points = (0..32).map { i -> p(i * 15L, 100 - i / 4) }
        val d = DrainAnalysis.analyze(points, emptyList(), emptyMap(), h)
        assertEquals(8.0, d.totalDrop, 1e-9)
        assertEquals(8.0, d.standbyDrop, 1e-9)
        assertTrue("nothing should be pinned on apps or Other", d.apps.isEmpty())
    }

    @Test fun appSharesPlusIdleAddUpToTheTotal() {
        val points = listOf(p(0, 100), p(60, 99), p(120, 98), p(150, 92), p(180, 87), p(240, 86))
        val screen = listOf(Span(t + 120 * m, t + 180 * m))
        val apps = mapOf("a" to listOf(Span(t + 120 * m, t + 165 * m)), "b" to listOf(Span(t + 165 * m, t + 180 * m)))
        val d = DrainAnalysis.analyze(points, screen, apps, h)
        assertEquals(d.totalDrop, d.standbyDrop + d.apps.sumOf { it.percent }, 1e-9)
    }

    @Test fun widgetWobbleAndGlitchesDoNotAddBatteryUsed() {
        val wobble = BatteryStats.device(listOf(r(0, 50), r(15, 51), r(30, 50), r(45, 51), r(60, 49)), hourOf)
        assertEquals(1, wobble.percentUsed)
        val glitch = BatteryStats.device(listOf(r(0, 50), r(15, 0), r(30, 50), r(45, 49)), hourOf)
        assertEquals(1, glitch.percentUsed)
        assertEquals(49, glitch.lowest!!.first)
        assertTrue(glitch.charges.isEmpty())
    }

    @Test fun realRechargeStillResetsTheBaseline() {
        // 60 -> 50, unseen charge to 80, then 80 -> 70: 20 points used in all.
        val s = BatteryStats.device(listOf(r(0, 60), r(60, 50), r(120, 80), r(180, 70)), hourOf)
        assertEquals(20, s.percentUsed)
    }

    @Test fun plannerChargeSpeedStopsAtTheFirstFullReading() {
        val readings = listOf(r(0, 20, true), r(40, 60, true), r(80, 100, true), r(200, 100, true), r(440, 100, true))
        val c = ChargeCurves.observedCharges(readings, GapLimits.forInterval(240)).single()
        assertEquals(t + 80 * m, c.peakMs)
        assertEquals(t + 440 * m, c.endMs)
    }

    @Test fun topUpWalksTheSlowerBandsInsteadOfStretchingTheFastOne() {
        val charge = ObservedCharge(t, t + 70 * m, 20, 100,
            listOf(BandTiming(20, 50, 10.0, null), BandTiming(50, 80, 20.0, null), BandTiming(80, 100, 40.0, null)), null, null, null)
        assertEquals(30.0 + 30.0 * 5 / 20, ChargeCurves.topUpPercent(listOf(charge), 15.0)!!, 1e-9)
        assertEquals(80.0, ChargeCurves.topUpPercent(listOf(charge), 500.0)!!, 1e-9)
    }

    @Test fun serviceSpansAreMergedBeforeCounting() {
        val merged = DrainAnalysis.mergeSpans(listOf(Span(0, 10), Span(5, 20), Span(30, 40), Span(40, 45)))
        assertEquals(listOf(Span(0, 20), Span(30, 45)), merged)
    }
}
