package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the 2026-11-18 review fixes. */
class ArgonReviewFixesTest {
    private val m = 60_000L
    private val h = 60 * m
    private val t = 1_000_000_000_000L

    // F1: a late pause or stop from an earlier activity must not end the next one's span.
    @Test fun lateStopFromThePreviousActivityKeepsTheNextSpan() {
        val f = ForegroundSpans()
        f.resumed("app", "A1", 0); f.pausedOrStopped("app", "A1", 10)
        f.resumed("app", "A2", 11)
        f.pausedOrStopped("app", "A1", 20)          // A1's late stop
        f.close(100)
        assertEquals(listOf(Span(0, 10), Span(11, 100)), f.spans["app"])
    }

    @Test fun movingBetweenActivitiesOfOneAppKeepsOneSpan() {
        val f = ForegroundSpans()
        f.resumed("app", "A1", 0); f.resumed("app", "A2", 5)
        f.pausedOrStopped("app", "A1", 7)
        f.pausedOrStopped("app", "A2", 50)
        f.close(100)
        assertEquals(listOf(Span(0, 50)), f.spans["app"])
    }

    @Test fun lateStopFromAnotherAppDoesNotEndTheCurrentOne() {
        val f = ForegroundSpans()
        f.resumed("a", "A1", 0); f.resumed("b", "B1", 10)
        f.pausedOrStopped("a", "A1", 12)
        f.close(30)
        assertEquals(listOf(Span(0, 10)), f.spans["a"])
        assertEquals(listOf(Span(10, 30)), f.spans["b"])
    }

    // F2: a real low between two readings hours apart is not a glitch.
    @Test fun aLowAcrossAMultiHourGapIsKept() {
        fun r(min: Long, pct: Int) = Reading("phone", t + min * m, pct, false, "android")
        val readings = listOf(r(0, 50), r(240, 35), r(480, 50), r(495, 49))
        val kept = BatteryMath.withoutSpikes(readings, { it.percent }, { it.charging }, { it.timeMs }, GapLimits())
        assertEquals(readings, kept)
        val s = BatteryStats.device(readings, { ((it - t) / h % 24).toInt() })
        assertEquals(35, s.lowest!!.first)
        assertEquals(16, s.percentUsed)
        // The same shape 15 minutes apart is still a glitch.
        assertEquals(2, BatteryMath.withoutSpikes(listOf(r(0, 50), r(15, 35), r(30, 50)).map(BatteryPoint::of)).size)
    }

    // F7: a full hold that reaches the end of history is still on the charger.
    @Test fun trailingFullHoldCountsAsChargingThroughTheLastReading() {
        fun w(min: Long, pct: Int, charging: Boolean? = false) = Reading("ring", t + min * m, pct, charging, SamsungWidgetSource.SOURCE)
        val r = BatteryStats.fullHoldAsCharging(listOf(w(0, 89, true), w(60, 100), w(120, 100), w(180, 100)))
        assertEquals(listOf(true, true, true, true), r.map { it.charging })
        // With a lower reading after it, the last 100% is still where the drain clock starts.
        val ended = BatteryStats.fullHoldAsCharging(listOf(w(0, 89, true), w(60, 100), w(120, 100), w(180, 99)))
        assertEquals(listOf(true, true, false, false), ended.map { it.charging })
    }

    // F8: drain is split at local clock hours, also in a half-hour zone.
    @Test fun drainByHourSplitsAtLocalHoursInAHalfHourZone() {
        val offset = 5 * h + 30 * m
        fun r(hours: Double, pct: Int) = Reading("phone", (hours * h).toLong(), pct, false, "android")
        // 00:00 to 01:00 UTC is 05:30 to 06:30 local: half in local hour 5, half in hour 6.
        val s = BatteryStats.device(listOf(r(0.0, 100), r(1.0, 90)), { ((it + offset) / h % 24).toInt() },
            utcOffsetMs = { offset })
        assertEquals(10.0, s.drainByHour[5]!!, 1e-9)
        assertEquals(10.0, s.drainByHour[6]!!, 1e-9)
    }

    // F8: unplugging within one check, what was lost since the charging reading counts as used.
    @Test fun dropRightAfterUnpluggingCountsAsUsed() {
        fun r(min: Long, pct: Int, charging: Boolean? = false) = Reading("phone", t + min * m, pct, charging, "android")
        val hourOf = { ms: Long -> ((ms - t) / h % 24).toInt() }
        assertEquals(3, BatteryStats.device(listOf(r(0, 80, true), r(15, 78), r(30, 77)), hourOf).percentUsed)
        // Across a gap the step is unknown, as before.
        assertEquals(0, BatteryStats.device(listOf(r(0, 80, true), r(180, 70)), hourOf).percentUsed)
    }

    // F5: with unequal run counts the headline and its range both come from Welch.
    @Test fun unequalRunCountsUseWelchForTheHeadlineAndTheRange() {
        fun run(id: Long, mode: String, rate: Double) =
            ExperimentResult(Experiment(id, "ring", mode, t + id * 24 * h, 90, t + id * 24 * h + 10 * h, 80), rate, null, 5, null)
        val results = listOf(run(1, "baseline", 1.0), run(2, "monitoring", 1.5), run(3, "baseline", 1.0),
            run(4, "monitoring", 1.6), run(5, "baseline", 3.0))
        val c = ExperimentStats.compare(results, "baseline", "monitoring")
        // The paired range alone would not contain the unpaired difference of the means.
        assertTrue(c.paired!!.low > c.meanB!! - c.meanA!!)
        assertTrue(c.chosen === c.welch)
        val d = c.difference!!
        assertEquals(c.meanB!! - c.meanA!!, d, 1e-9)
        assertTrue(c.chosen!!.low <= d && d <= c.chosen!!.high)
        // Equal counts still use the paired comparison.
        assertTrue(ExperimentStats.compare(results.take(4), "baseline", "monitoring").let { it.chosen === it.paired })
    }

    // F13: background Ring reads and monitoring after a restore follow the running test's arm.
    @Test fun ringReadsFollowTheRunningTest() {
        assertTrue(ExperimentEndpoint.backgroundReadAllowed(true, true, null))
        assertTrue(ExperimentEndpoint.backgroundReadAllowed(true, true, "monitoring"))
        for (mode in listOf("baseline", "ring_alone", "ring_with_watch"))
            assertEquals(false, ExperimentEndpoint.backgroundReadAllowed(true, true, mode))
        assertEquals(false, ExperimentEndpoint.backgroundReadAllowed(false, true, null))
        assertEquals(false, ExperimentEndpoint.backgroundReadAllowed(true, false, null))
        assertTrue(ExperimentEndpoint.monitoringAfterImport(true, "monitoring"))
        assertEquals(false, ExperimentEndpoint.monitoringAfterImport(true, "baseline"))
        assertEquals(false, ExperimentEndpoint.monitoringAfterImport(false, null))
    }

    // F9: screen-on time with no app in the history counts toward Other's hours and intensity.
    @Test fun bareScreenTimeGivesOtherItsHoursAndIntensity() {
        fun p(min: Long, pct: Int) = BatteryPoint(t + min * m, pct, false)
        // 1%/h idle with the screen off; one hour on screen with no app: 8 points.
        val points = listOf(p(0, 100), p(60, 99), p(120, 98), p(180, 90), p(240, 89))
        val d = DrainAnalysis.analyze(points, listOf(Span(t + 120 * m, t + 180 * m)), emptyMap(), h)
        val other = d.apps.single { it.app == DrainAnalysis.UNATTRIBUTED }
        assertEquals(7.0, other.percent, 1e-9)
        assertEquals(1.0, other.foregroundHours, 1e-9)
        assertEquals(8.0, other.activePctPerHour!!, 1e-9)
    }
}
