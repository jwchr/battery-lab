package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastAndExperimentTest {
    private val m = 60_000L
    private val h = 60 * m
    private val t0 = 1_000_000_000_000L
    private fun r(min: Long, pct: Int, charging: Boolean? = false) = Reading("phone", t0 + min * m, pct, charging, "android")
    private fun forecast(start: Int = 80, rate: Double = 2.0, android: Double? = 40.0) =
        Forecast(1, "phone", t0, start, rate, start / rate, android, true, ForecastScoring.MODEL_VERSION, null, null, null, "PENDING")

    /** These fixtures are sparse on purpose; the gap check has its own tests with the default step. */
    private val sparse = GapLimits(stepMs = 4 * h)

    @Test fun staysPendingUntilEightHoursPass() {
        val s = ForecastScoring.score(forecast(), listOf(r(120, 76)), t0 + 3 * h, sparse)
        assertEquals("PENDING", s.status)
        assertEquals(76.0, s.actual2h!!, 1e-9)
    }

    @Test fun interpolatesBetweenReadingsAroundEachHorizon() {
        val readings = listOf(r(110, 77), r(130, 75), r(240, 72), r(470, 66), r(490, 64))
        val s = ForecastScoring.score(forecast(), readings, t0 + 10 * h, sparse)
        assertEquals("SCORED", s.status)
        assertEquals(76.0, s.actual2h!!, 1e-9)
        assertEquals(72.0, s.actual4h!!, 1e-9)
        assertEquals(65.0, s.actual8h!!, 1e-9)
    }

    @Test fun chargingCensorsLaterHorizons() {
        val readings = listOf(r(120, 76), r(180, 76, true), r(240, 90), r(480, 85))
        val s = ForecastScoring.score(forecast(), readings, t0 + 10 * h, sparse)
        assertEquals(76.0, s.actual2h!!, 1e-9)
        assertNull(s.actual4h)
        assertNull(s.actual8h)
    }

    @Test fun chargedBeforeFirstHorizonIsCensored() {
        val s = ForecastScoring.score(forecast(), listOf(r(30, 80, true), r(120, 95)), t0 + 10 * h)
        assertEquals("CENSORED_CHARGED", s.status)
    }

    /** 1.P2j-i: a hole longer than one step inside the window could hide a charge and a drain. */
    @Test fun aHoleInsideTheWindowCensorsThatHorizonAndLaterOnes() {
        val steady = (0..16).map { i -> r(i * 15L, 80 - i / 4) }          // every 15 min for 4 h
        val after = listOf(r(330, 69), r(345, 68)) + (24..34).map { i -> r(i * 15L, 80 - i / 2) }
        val s = ForecastScoring.score(forecast(), steady + after, t0 + 10 * h)
        assertEquals(78.0, s.actual2h!!, 1e-9)
        assertEquals(76.0, s.actual4h!!, 1e-9)
        assertNull("90 minutes unseen between 4 h and 5.5 h", s.actual8h)
        assertEquals("SCORED", s.status)
        // The same readings with no hole score all three.
        val full = (0..34).map { i -> r(i * 15L, 80 - i / 4) }
        assertEquals(72.0, ForecastScoring.score(forecast(), full, t0 + 10 * h).actual8h!!, 1e-9)
    }

    @Test fun aLongWaitForTheFirstReadingIsAGap() {
        val late = (6..34).map { i -> r(i * 15L, 80 - i / 4) }             // nothing for 90 min after the forecast
        val s = ForecastScoring.score(forecast(), late, t0 + 10 * h)
        assertNull(s.actual2h); assertNull(s.actual8h)
        assertEquals("CENSORED_GAP", s.status)
    }

    @Test fun noReadingsNearHorizonsIsAGap() {
        val s = ForecastScoring.score(forecast(), listOf(r(10, 79)), t0 + 10 * h)
        assertEquals("CENSORED_GAP", s.status)
    }

    @Test fun reportCardGivesErrorAndBiasPerSource() {
        // Lab predicts 76 at 2 h (2%/h); Android predicts 80 - 80/40*2 = 76 too. Actuals 75 and 77.
        val a = forecast().copy(actual2h = 75.0, status = "SCORED")
        val b = forecast().copy(id = 2, actual2h = 77.0, status = "SCORED")
        val card = ForecastScoring.reportCard(listOf(a, b))
        val two = card.first { it.hours == 2 }
        assertEquals(2, two.n)
        assertEquals(1.0, two.labMae!!, 1e-9)
        assertEquals(0.0, two.labBias!!, 1e-9)
        assertEquals(2, two.androidN)
        assertEquals(0, card.first { it.hours == 8 }.n)
    }

    private fun experiment(id: Long, mode: String, startH: Long, startPct: Int, hours: Long, endPct: Int) =
        Experiment(id, "ring", mode, t0 + startH * h, startPct, t0 + (startH + hours) * h, endPct)

    @Test fun experimentRateFromChangesNotRepeats() {
        val e = experiment(1, "baseline", 0, 80, 10, 70)
        val readings = (0..40).map { i -> r(i * 15L, 80 - i / 4) }    // 1%/h, each value repeated 4 times
        val res = ExperimentStats.result(e, readings)
        assertNull(res.quarantine)
        assertEquals(1.0, res.rate!!, 0.1)
        assertTrue(res.transitions <= 12)
    }

    @Test fun experimentIsQuarantinedWhenCharged() {
        val e = experiment(1, "baseline", 0, 80, 10, 90)
        val res = ExperimentStats.result(e, listOf(r(60, 79), r(120, 85, true)))
        assertNotNull(res.quarantine)
        assertNull(res.rate)
        assertNotNull(ExperimentStats.result(e.copy(endMs = null, endPct = null), emptyList()).quarantine)
    }

    @Test fun pairedAndWelchComparisons() {
        fun run(id: Long, mode: String, rate: Double) =
            ExperimentResult(Experiment(id, "ring", mode, t0 + id * 24 * h, 90, t0 + id * 24 * h + 10 * h, 80), rate, null, 5, null)
        val results = listOf(run(1, "baseline", 1.0), run(2, "monitoring", 1.5), run(3, "baseline", 1.2),
            run(4, "monitoring", 1.6), run(5, "baseline", 0.8), run(6, "monitoring", 1.4))
        val c = ExperimentStats.compare(results, "baseline", "monitoring")
        assertEquals(3, c.nA); assertEquals(3, c.nB)
        assertEquals(0.5, c.paired!!.estimate, 1e-9)
        assertTrue(c.paired!!.low < 0.5 && c.paired!!.high > 0.5)
        assertEquals(0.5, c.welch!!.estimate, 1e-9)
        assertEquals(1.0 / 1.5 - 1, c.runtimeChange!!, 1e-9)
        assertNull(ExperimentStats.compare(results.take(2), "baseline", "monitoring").paired)
    }

    @Test fun tTableIsSane() {
        // Printed two-sided 95% t-table values.
        for ((df, t) in listOf(1.0 to 12.706, 2.0 to 4.303, 3.0 to 3.182, 5.0 to 2.571, 10.0 to 2.228,
                30.0 to 2.042, 120.0 to 1.980)) assertEquals("df $df", t, ExperimentStats.tCrit(df), 0.002)
        val mid = ExperimentStats.tCrit(11.0)
        assertTrue(mid < 2.228 && mid > 2.179)
        // Between 1 and 2 df the curve bends hard: a straight line between the table rows gave 8.50
        // at df 1.5; the t density puts 95% of its area inside +-6.017 (1.P2k-iii).
        assertEquals(6.017, ExperimentStats.tCrit(1.5), 0.002)
        assertEquals(3.575, ExperimentStats.tCrit(2.5), 0.002)
        // No jump at 120 df: just above it the value keeps falling smoothly toward 1.96.
        assertEquals(1.980, ExperimentStats.tCrit(121.0), 0.002)
        assertEquals(1.965, ExperimentStats.tCrit(500.0), 0.002)
    }

    @Test fun readinessPlannerKeepsAReserveAndOrdersCharging() {
        val ok = ReadinessPlanner.assess("Phone", 80, listOf(2.0, 2.0, 2.0), 1.0, 12.0, 15)
        assertEquals(32.5, ok.hoursToReserve!!, 1e-9)
        assertEquals(true, ok.meetsTarget)
        assertEquals(0.0, ok.topUpMinutes!!, 1e-9)
        val short = ReadinessPlanner.assess("Ring", 30, listOf(3.0), 0.5, 12.0, 15)
        assertEquals(false, short.meetsTarget)
        assertEquals(42.0, short.topUpMinutes!!, 1e-9)     // needs 3 × 12 + 15 − 30 = 21 points at 0.5%/min
        val unknown = ReadinessPlanner.assess("Watch", 50, emptyList(), null, 12.0, 15)
        assertNull(unknown.meetsTarget)
        assertEquals(listOf("Ring"), ReadinessPlanner.order(listOf(ok, short, unknown)).map { it.name })
    }

    @Test fun updateImpactNeedsSessionsOnBothSides() {
        fun s(day: Int, rate: Double) = IdleStretch(t0 + day * 24 * h, t0 + day * 24 * h + 8 * h, 90, (90 - rate * 8).toInt(),
            0.0, 0.0, 0.0, null, null)
        val entry = JournalEntry(1, t0 + 10 * 24 * h, t0 + 10 * 24 * h - h, "phone", "os_update", "Update", null)
        val few = UpdateImpactAnalysis.assess(entry, listOf(s(1, 1.0), s(2, 1.0), s(11, 2.0)))
        assertNull(few.strength)
        val stretches = (1..4).map { s(it, 1.0) } + (11..14).map { s(it, 2.0) }
        val im = UpdateImpactAnalysis.assess(entry, stretches)
        assertEquals("Preliminary", im.strength)
        assertEquals(1.0, im.delta!!.estimate, 1e-9)
    }

    @Test fun aReadingAfterATopUpIsNotTheActual() {
        // 2 h mark at 120 min: 77% at 110, plugged in at 125, unplugged at 88% at 145.
        val s = ForecastScoring.score(forecast(), listOf(r(110, 77), r(125, 80, true), r(145, 88)), t0 + 10 * h, sparse)
        org.junit.Assert.assertNull(s.actual2h)
        assertEquals("CENSORED_CHARGED", s.status)
    }
}
