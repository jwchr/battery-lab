package com.batterylab.personal

import org.junit.Assert.assertEquals
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
