package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentEndpointTest {
    private val now = 1_000_000_000_000L
    private val m = 60_000L
    private val widget = "samsung_widget"
    private fun r(ageMin: Long, pct: Int, source: String = widget) = Reading("ring", now - ageMin * m, pct, null, source)
    private fun choose(mode: String, shown: Int?, latest: Reading?, address: Boolean = true) =
        ExperimentEndpoint.choose(mode, shown, latest, now, address, widget)

    @Test fun usesDeduplicatedWidgetReadingEvenWhenItsTimestampIsOld() {
        // The widget still shows 71%; the stored row is from when 71% was first seen 4 min ago.
        val latest = r(4, 71)
        assertEquals(ExperimentEndpoint.Choice.Use(latest), choose("baseline", 71, latest))
    }

    @Test fun baselineNeverFallsBackToBle() {
        val c = choose("baseline", null, r(30, 71))
        assertTrue(c is ExperimentEndpoint.Choice.Refuse)
    }

    @Test fun otherZeroRadioArmsNeverFallBackToBle() {
        for (mode in listOf("ring_alone", "ring_with_watch"))
            assertTrue(mode, choose(mode, null, null) is ExperimentEndpoint.Choice.Refuse)
    }

    @Test fun monitoringFallsBackToBleOnlyWithAPairedRing() {
        assertEquals(ExperimentEndpoint.Choice.ReadBle, choose("monitoring", null, null, address = true))
        assertTrue(choose("monitoring", null, null, address = false) is ExperimentEndpoint.Choice.Refuse)
    }

    @Test fun widgetShowingDifferentValueThanStoredDoesNotReuseStaleRow() {
        assertTrue(choose("baseline", 70, r(1, 71)) is ExperimentEndpoint.Choice.Refuse)
    }

    @Test fun recentManualEntryIsUsedOldOneIsNot() {
        val fresh = r(1, 64, "manual_widget")
        assertEquals(ExperimentEndpoint.Choice.Use(fresh), choose("baseline", null, fresh))
        assertTrue(choose("baseline", null, r(3, 64, "manual_widget")) is ExperimentEndpoint.Choice.Refuse)
    }

    @Test fun bleReadingIsNotReusedAsWidgetValue() {
        assertTrue(choose("baseline", 71, r(0, 71, "ring_ble")) is ExperimentEndpoint.Choice.Refuse)
    }
}
