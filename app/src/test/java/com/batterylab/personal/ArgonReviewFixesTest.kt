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
