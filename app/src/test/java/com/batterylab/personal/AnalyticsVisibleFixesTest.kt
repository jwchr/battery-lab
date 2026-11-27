package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner-approved user-visible fixes from the Neon deep review of Nov 26, 2026. All fixtures are synthetic. */
class AnalyticsVisibleFixesTest {
    private val m = 60_000L
    private val h = 3_600_000L
    private val t0 = 1_000_000_000_000L

    private fun forecast(id: Long, android: Double?, actual2h: Double) =
        Forecast(id, "phone", t0, 80, 2.0, 40.0, android, true, ForecastScoring.MODEL_VERSION, actual2h, null, null, "SCORED")

    /** 1.P2j-iii: Android's error was set beside Battery Lab's over a different set of forecasts. */
    @Test fun batteryLabIsAlsoScoredOnTheForecastsAndroidMade() {
        // Both predict 76 at 2 h. Android gave no estimate for the second one, where Lab missed by 6.
        val card = ForecastScoring.reportCard(listOf(forecast(1, 40.0, 76.0), forecast(2, null, 70.0), forecast(3, 40.0, 74.0)))
        val two = card.first { it.hours == 2 }
        assertEquals(3, two.n)
        assertEquals(8.0 / 3, two.labMae!!, 1e-9)
        assertEquals(2, two.androidN)
        assertEquals(1.0, two.androidMae!!, 1e-9)
        assertEquals("same two forecasts", 1.0, two.pairedLabMae!!, 1e-9)
    }

    private fun stretch(day: Int, cell: Double, doze: Double, temp: Double, rate: Double) =
        IdleStretch(t0 + day * 24 * h, t0 + day * 24 * h + 8 * h, 90, (90 - rate * 8).toInt(), 0.0, cell, 0.0, doze, temp)
    private val update = JournalEntry(1, t0 + 10 * 24 * h, t0 + 10 * 24 * h - h, "phone", "os_update", "Update", null)

    /** 1.P3c: only the first confounder was shown, and temperature and Doze were never checked. */
    @Test fun everyConfounderIsListed() {
        val stretches = (1..4).map { stretch(it, 0.0, 0.8, 25.0, 1.0) } + (11..14).map { stretch(it, 0.5, 0.3, 32.0, 2.0) }
        assertEquals("Confounded: cellular time went from 0% to 50%; deep-sleep (Doze) time went from 80% to 30%; " +
            "average temperature went from 25°C to 32°C", UpdateImpactAnalysis.assess(update, stretches).confounder)
    }

    @Test fun smallShiftsAreNotConfounders() {
        val stretches = (1..4).map { stretch(it, 0.1, 0.8, 25.0, 1.0) } + (11..14).map { stretch(it, 0.3, 0.6, 28.0, 2.0) }
        assertNull(UpdateImpactAnalysis.assess(update, stretches).confounder)
        val warmer = (1..4).map { stretch(it, 0.1, 0.8, 25.0, 1.0) } + (11..14).map { stretch(it, 0.1, 0.8, 29.0, 2.0) }
        assertEquals("Confounded: average temperature went from 25°C to 29°C", UpdateImpactAnalysis.assess(update, warmer).confounder)
    }

    /** 1.P2i-ii: a one-hour heavy stretch weighed as much as a whole quiet day. */
    @Test fun drainRatesCountByHowLongTheyLasted() {
        val rates = listOf(1.0, 10.0, 10.0)
        val unweighted = ReadinessPlanner.assess("Phone", 80, rates, null, 12.0, 15)
        assertEquals(6.5, unweighted.hoursToReserve!!, 1e-9)
        val weighted = ReadinessPlanner.assess("Phone", 80, rates, null, 12.0, 15, stretchHours = listOf(24.0, 1.0, 1.0))
        assertTrue("the long quiet day dominates: ${weighted.hoursToReserve}", weighted.hoursToReserve!! > 30.0)
        assertEquals(true, weighted.meetsTarget)
    }

    @Test fun equalWeightsGiveTheUsualMedian() {
        assertEquals(2.0, ReadinessPlanner.weightedQuantile(listOf(3.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0), 0.5), 1e-9)
        assertEquals(1.0, ReadinessPlanner.weightedQuantile(listOf(1.0 to 1.0, 2.0 to 1.0), 0.1), 1e-9)
        assertEquals(2.0, ReadinessPlanner.weightedQuantile(listOf(1.0 to 1.0, 2.0 to 1.0), 0.9), 1e-9)
    }

    /** 1.P2i-ii: the top-up was priced at the whole charge's average speed, missing the slow top. */
    @Test fun topUpIsTimedStepByStep() {
        // 85% at 4%/h for 20 h with a 15% reserve needs 10 more points (to 95%).
        val linear = ReadinessPlanner.assess("Phone", 85, listOf(4.0), 1.0, 20.0, 15)
        assertEquals(10.0, linear.topUpMinutes!!, 1e-9)
        // 80->90 takes 20 min and 90->100 takes 40: half of each is 30 min.
        val stepped = ReadinessPlanner.assess("Phone", 85, listOf(4.0), 1.0, 20.0, 15, chargeSteps = mapOf(80 to 20.0, 90 to 40.0))
        assertEquals(30.0, stepped.topUpMinutes!!, 1e-9)
        // With no step times for that range, the average speed is still used.
        val fallback = ReadinessPlanner.assess("Phone", 85, listOf(4.0), 1.0, 20.0, 15, chargeSteps = mapOf(20 to 5.0))
        assertEquals(10.0, fallback.topUpMinutes!!, 1e-9)
    }

    /** 1.P2m-i: sparse deep-sleep polls counted once each, so awake time dominated the shares. */
    @Test fun idleSharesAndTemperatureAreTimeWeighted() {
        fun r(min: Long, idle: Boolean, tempC: Int) = Reading("phone", t0 + min * m, 90 - (min / 30).toInt(), false, "android",
            tempTenthsC = tempC * 10, detail = ReadingDetail(displayState = 1, deviceIdle = idle, transport = "wifi"))
        val awake = (0 until 48).map { r(it * 5L, false, 35) }               // 4 h, every 5 minutes
        val asleep = (0..8).map { r(240 + it * 60L, true, 25) }              // 8 h, once an hour
        val s = IdleAnalysis.stretches(awake + asleep).single()
        assertEquals(480.0 / 720, s.dozeShare!!, 1e-9)
        assertEquals((240 * 35.0 + 480 * 25.0) / 720, s.avgTempC!!, 1e-9)
        assertEquals(0.0, s.cellularShare!!, 1e-9)
    }

    /** 1.P2n-ii: per-app mAh, the audit and fun facts used another capacity than the headline. */
    @Test fun everyCapacityUseFollowsTheHeadline() {
        val audit = CapacityEstimate(null, 0, null, null, 0, 4200.0, null, 3)
        val proxy = CapacityProxy.summarize(emptyList(), t0)
        assertEquals(4200.0, StatsPrecomputer.Health(audit, proxy.copy(estimateMah = null)).headlineMah!!, 1e-9)
        assertEquals(3900.0, StatsPrecomputer.Health(audit, proxy.copy(estimateMah = 3900.0)).headlineMah!!, 1e-9)
    }

    private fun pr(min: Long, pct: Int, charging: Boolean? = false) = Reading("phone", t0 + min * m, pct, charging, "android")
    private val pending = Forecast(1, "phone", t0, 80, 2.0, 40.0, null, null, ForecastScoring.MODEL_VERSION, null, null, null, "PENDING")
    private fun draining(fromMin: Long, toMin: Long) = (fromMin..toMin step 15).map { pr(it, (80 - it / 30).toInt()) }

    /** 88.1: a glitch in the first reading after a forecast was kept (no reading before it) and censored every horizon. */
    @Test fun aGlitchRightAfterTheForecastIsDropped() {
        val s = ForecastScoring.score(pending, listOf(pr(5, 99)) + draining(15, 540), t0 + 10 * h)
        assertEquals("SCORED", s.status)
        assertEquals(76.0, s.actual2h!!, 1e-9)
    }

    /** 88.1: a glitch in the newest reading censored a horizon before the next reading could show it was a glitch. */
    @Test fun aRiseInTheNewestReadingWaitsForTheNextOne() {
        val upTo = draining(0, 105) + pr(125, 99)
        assertEquals("PENDING", ForecastScoring.score(pending, upTo, t0 + 151 * m).status)
        val next = ForecastScoring.score(pending, upTo + pr(140, 75), t0 + 151 * m)
        assertEquals(77.0 + (75 - 77) * 15.0 / 35, next.actual2h!!, 1e-9)
        // The same when the newest reading falls just before the horizon.
        val early = draining(0, 105) + pr(115, 99)
        assertEquals("PENDING", ForecastScoring.score(pending, early, t0 + 151 * m).status)
        assertEquals(77.0 + (75 - 77) * 15.0 / 35, ForecastScoring.score(pending, early + pr(140, 75), t0 + 151 * m).actual2h!!, 1e-9)
        // A reading marked charging needs no wait.
        assertEquals("CENSORED_CHARGED", ForecastScoring.score(pending, draining(0, 105) + pr(125, 99, true), t0 + 10 * h).status)
    }

    @Test fun theWaitForAConfirmingReadingEnds() {
        val s = ForecastScoring.score(pending, draining(0, 105) + pr(125, 99), t0 + 10 * h)
        assertEquals("CENSORED_CHARGED", s.status)
    }

    /** 88.1: the glitch filter used the default 60-minute step, not the caller's, so sparse glitches stayed. */
    @Test fun theGlitchFilterUsesTheCallersGapLimits() {
        val s = ForecastScoring.score(pending, listOf(pr(0, 80), pr(90, 99), pr(120, 77), pr(240, 72), pr(480, 64)),
            t0 + 10 * h, GapLimits(stepMs = 3 * h))
        assertEquals(77.0, s.actual2h!!, 1e-9)
    }
}
