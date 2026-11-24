package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedBugRegressionTest {
    private val hour = 3_600_000L

    @Test fun finishedBluetoothRunWithTooFewDirectReadingsIsSetAside() {
        val run = Experiment(1, "test", "monitoring", 0, 80, 5 * hour, 70)
        val widget = listOf(
            Reading("test", 0, 80, false, "samsung_widget"),
            Reading("test", 5 * hour, 70, false, "samsung_widget")
        )
        val ble = (1..4).map { Reading("test", it * hour, 80 - it, false, "ring_ble") }
        val short = ExperimentStats.result(run, widget + ble.take(3))
        assertEquals("Too few direct Ring readings", short.quarantine)
        assertNull(short.rate)
        assertEquals(1, armDetail(listOf(short), "monitoring").setAside)
        assertEquals(0, armDetail(listOf(short), "monitoring").runs)

        val fitted = ExperimentStats.result(run, widget + ble)
        assertNull(fitted.quarantine)
        assertNull(fitted.endpointRate)
        assertEquals(1.0, fitted.rate!!, 1e-9)

        val baseline = ExperimentStats.result(run.copy(mode = "baseline"), widget + ble.take(3))
        assertNull(baseline.quarantine)
        assertEquals(2.0, baseline.rate!!, 1e-9)
        val running = ExperimentStats.result(run.copy(endMs = null, endPct = null), widget + ble.take(3))
        assertEquals("Still running", running.quarantine)
        val matched = ExperimentStats.result(run.copy(startPct = 79, endMs = 3 * hour, endPct = 77),
            listOf(ble.first().copy(timeMs = 0), ble[1], ble[2]))
        assertNull(matched.quarantine)
        assertEquals(2.0 / 3, matched.rate!!, 1e-9)
        val emptyBaseline = ExperimentStats.result(run.copy(mode = "baseline", endPct = null), emptyList())
        assertNull(emptyBaseline.quarantine)
        assertNull(emptyBaseline.rate)
    }

    @Test fun overlappingServicesKeepTheLongSpanAndCloseAtBoundaries() {
        fun event(type: Int, cls: String?, time: Long, pkg: String = "test.app") =
            UsageSource.ServiceEvent(type, pkg, cls, time)
        val end = 6 * hour
        val events = listOf(event(19, "A", 0), event(19, "B", 300_000),
            event(20, "B", 302_000), event(20, "A", end))
        val spans = UsageSource.serviceSpans(events, end)
        assertEquals(listOf(Span(300_000, 302_000), Span(0, end)), spans["test.app"])
        val points = (0..6).map { BatteryPoint(it * hour, 80 - it, false) }
        val drain = DrainAnalysis.analyze(points, emptyList(), emptyMap(), hour, services = spans)
        assertEquals(6.0, drain.apps.single().backgroundServiceHours, 1e-9)

        val open = events.take(2)
        assertEquals(listOf(Span(0, end), Span(300_000, end)).toSet(),
            UsageSource.serviceSpans(open, end)["test.app"]!!.toSet())
        val shutdown = open + event(26, null, hour) + event(19, "A", 2 * hour)
        assertEquals(setOf(Span(0, hour), Span(300_000, hour), Span(2 * hour, end)),
            UsageSource.serviceSpans(shutdown, end)["test.app"]!!.toSet())
        val unknown = listOf(event(20, "A", 1), event(19, null, 2), event(19, null, 3),
            event(20, "", 4), event(19, "A", 5), event(20, "A", 5))
        assertEquals(listOf(Span(2, 4)), UsageSource.serviceSpans(unknown, 6)["test.app"])
        val separate = listOf(event(19, "A", 0), event(19, "A", 1, "test.other"),
            event(20, "A", 2, "test.other"), event(20, "A", 3))
        assertEquals(mapOf("test.app" to listOf(Span(0, 3)), "test.other" to listOf(Span(1, 2))),
            UsageSource.serviceSpans(separate, 4))
    }

    @Test fun temperatureIncludesSubzeroAndZeroWithinSupportedBounds() {
        fun reading(temp: Int?, charging: Boolean = false) =
            Reading("test", 0, 50, charging, "test", temp)
        val stats = BatteryStats.temperature(listOf(reading(-20), reading(0, true)))!!
        assertEquals(-2.0, stats.minC, 1e-9)
        assertEquals(0.0, stats.maxC, 1e-9)
        assertEquals(-1.0, stats.avgC, 1e-9)
        assertEquals(-2.0, stats.avgOnBatteryC!!, 1e-9)
        assertEquals(0.0, stats.avgChargingC!!, 1e-9)
        val bounds = BatteryStats.temperature(listOf(reading(-401), reading(-400), reading(700),
            reading(701), reading(null)))!!
        assertEquals(-40.0, bounds.minC, 1e-9)
        assertEquals(70.0, bounds.maxC, 1e-9)
        assertEquals(15.0, bounds.avgC, 1e-9)
        assertNull(BatteryStats.temperature(listOf(reading(-401), reading(701), reading(null))))
    }
}
