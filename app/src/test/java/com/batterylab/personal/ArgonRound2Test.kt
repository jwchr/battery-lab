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
}
