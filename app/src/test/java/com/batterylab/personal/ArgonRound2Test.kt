package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the second 2026-11-18 review round. */
class ArgonRound2Test {
    private val m = 60_000L
    private val h = 60 * m
    private val t = 1_000_000_000_000L

    // R4: each arm uses one Ring source, so widget and Bluetooth values don't mix.
    @Test fun experimentUsesOnlyItsArmsRingSource() {
        fun ring(min: Long, pct: Int, source: String) = Reading(BatteryStore.RING_ID, t + min * m, pct, false, source)
        // Bluetooth drops 1 point every hour; the widget lags 3 points behind it.
        val readings = (0..8).flatMap { i ->
            listOf(ring(i * 60L, 90 - i, "ring_ble"), ring(i * 60L + 30, 93 - i, SamsungWidgetSource.SOURCE))
        }
        val monitored = ExperimentStats.result(Experiment(1, BatteryStore.RING_ID, "monitoring", t, 90, t + 8 * h, 82), readings)
        assertNull(monitored.quarantine)
        assertEquals(9, monitored.transitions)
        assertEquals(1.0, monitored.rate!!, 1e-9)
        val baseline = ExperimentStats.result(Experiment(2, BatteryStore.RING_ID, "baseline", t, 93, t + 8 * h + 30 * m, 85), readings)
        assertNull(baseline.quarantine)
        assertEquals(9, baseline.transitions)
        assertTrue(ExperimentStats.usesSource("baseline", "manual_widget"))
        assertEquals(false, ExperimentStats.usesSource("monitoring", "manual_widget"))
    }

    // R6: plugged in overnight, the slow finish ends at FULL or when the counter stops rising.
    @Test fun slowFinishStopsAtFullOrAFlatCounter() {
        fun p(min: Long, uah: Int?, status: Int? = null) = Reading("phone", t + min * m, 100, true, "android",
            remainingUah = uah, detail = status?.let { ReadingDetail(status = it) })
        // Counter rises for 30 min, then sits flat on the charger for 8 hours.
        val byCounter = listOf(p(0, 4_000_000), p(15, 4_050_000), p(30, 4_080_000)) +
            (1..32).map { p(30 + it * 15L, 4_080_000) }
        assertEquals(listOf(30.0 to 80.0), HonestyAuditor.cvTaper(byCounter, GapLimits()))
        // No counter: the charger's FULL status ends it.
        val byStatus = listOf(p(0, null, 2), p(20, null, 2), p(40, null, 5)) + (1..30).map { p(40 + it * 15L, null, 5) }
        assertEquals(listOf(40.0 to null), HonestyAuditor.cvTaper(byStatus, GapLimits()))
    }

    // R10: no lag when the widget already shows the new value; no match across a widget charge.
    @Test fun widgetLagIsZeroWhenAlreadyShownAndStopsAtACharge() {
        fun a(min: Long, pct: Int) = Reading("phone", t + min * m, pct, false, "android")
        fun w(min: Long, pct: Int, charging: Boolean? = false) = Reading("phone", t + min * m, pct, charging, SamsungWidgetSource.SOURCE)
        assertEquals(listOf(0.0), HonestyAuditor.widgetLags(listOf(a(0, 80), a(30, 79)), listOf(w(20, 79), w(50, 79))))
        // The widget charges before it ever shows 79: no lag is counted from the later 79.
        assertEquals(emptyList<Double>(), HonestyAuditor.widgetLags(listOf(a(0, 80), a(30, 79)),
            listOf(w(15, 80), w(45, 85, true), w(90, 79))))
    }

    // R13: a charge seen only after a gap is timed from its first charging reading; a history
    // that starts mid-charge still records the charge.
    @Test fun chargeSessionsAfterAGapAndAtTheStart() {
        fun r(min: Long, pct: Int, charging: Boolean? = false) = Reading("phone", t + min * m, pct, charging, "android")
        val hourOf = { ms: Long -> ((ms - t) / h % 24).toInt() }
        val gap = BatteryStats.device(listOf(r(0, 30), r(300, 40, true), r(330, 70, true), r(345, 70)), hourOf)
        val c = gap.charges.single()
        assertEquals(t + 300 * m, c.startMs)
        assertEquals(40, c.fromPct)
        assertEquals(1.0, c.ratePerMinute!!, 1e-9)
        val start = BatteryStats.device(listOf(r(0, 50, true), r(30, 80, true), r(45, 80)), hourOf)
        assertEquals(50, start.charges.single().fromPct)
        assertEquals(30.0, start.charges.single().minutes, 1e-9)
    }

    // R16: a stretch that began before the window but runs into it counts for that window.
    @Test fun rollingWindowKeepsStretchesThatOverlapIt() {
        val day = 24 * h
        val before = DischargeStretch(t - 9 * day, t - 8 * day, 100, 40)
        val spanning = DischargeStretch(t - 7 * day - 2 * h, t - 7 * day + 10 * h, 100, 30)
        val inside = DischargeStretch(t - day, t - day + 8 * h, 90, 50)
        assertEquals(listOf(spanning, inside), BatteryMath.overlapping(listOf(before, spanning, inside), t - 7 * day))
    }

    // R8: within a step, screen-off time costs idle; the rest of the drop goes to on-screen time.
    @Test fun appRateIsNotDilutedByScreenOffTimeInTheStep() {
        fun p(min: Long, pct: Int) = BatteryPoint(t + min * m, pct, false)
        // Idle 1%/h. The last hour lost 5 points with 15 minutes on screen in one app.
        val points = listOf(p(0, 100), p(60, 99), p(120, 98), p(180, 93))
        val on = listOf(Span(t + 120 * m, t + 135 * m))
        val d = DrainAnalysis.analyze(points, on, mapOf("a" to on), h)
        val a = d.apps.single { it.app == "a" }
        assertEquals(0.25, a.foregroundHours, 1e-9)
        assertEquals((5 - 0.75) / 0.25, a.activePctPerHour!!, 1e-9)
        assertEquals(d.totalDrop, d.standbyDrop + d.apps.sumOf { it.percent }, 1e-9)
    }

    // R5: a test with no finish is set aside after 14 hours, and only ever turns Bluetooth off.
    @Test fun overdueTestCapAndMonitoringAfterAbandon() {
        assertEquals(false, ExperimentEndpoint.pastCap(t, t + 14 * h))
        assertTrue(ExperimentEndpoint.pastCap(t, t + 14 * h + 1))
        // Monitoring arm: always off.
        assertEquals(false, ExperimentEndpoint.monitoringAfterAbandon("monitoring", true, true, byUser = true))
        assertEquals(false, ExperimentEndpoint.monitoringAfterAbandon("monitoring", true, null, byUser = false))
        // Other arms by hand: back to how it was before the test.
        assertTrue(ExperimentEndpoint.monitoringAfterAbandon("baseline", false, true, byUser = true))
        assertEquals(false, ExperimentEndpoint.monitoringAfterAbandon("baseline", false, null, byUser = true))
        // Automatically: never turned on.
        assertEquals(false, ExperimentEndpoint.monitoringAfterAbandon("baseline", false, true, byUser = false))
    }
}
