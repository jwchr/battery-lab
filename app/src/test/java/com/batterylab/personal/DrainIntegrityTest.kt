package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainIntegrityTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_000_000_000_000L
    private val capacityMah = 4000.0

    private fun counterStep(dropUah: Int): DrainBreakdown = DrainAnalysis.analyze(
        listOf(BatteryPoint(t0, 80, false, 3_200_000),
            BatteryPoint(t0 + 15 * minute, 79, false, 3_200_000 - dropUah)),
        emptyList(), emptyMap(), hour, capacityMah = capacityMah)

    @Test fun counterJumpFallsBackWithoutLosingQuantizedCounterSteps() {
        // Synthetic 4000 mAh capacity: 1,600,000 uAh is 40 points, 24,000 is 0.6.
        val jump = counterStep(1_600_000)
        assertEquals(1.0, jump.totalDrop, 1e-9)
        assertFalse(jump.usedCounter)

        val quantized = counterStep(24_000)
        assertEquals(0.6, quantized.totalDrop, 1e-9)
        assertTrue(quantized.usedCounter)

        // Only one of three steps uses the counter, so rejected jumps cannot win the majority.
        val mixed = DrainAnalysis.analyze(listOf(
            BatteryPoint(t0, 80, false, 3_200_000),
            BatteryPoint(t0 + 15 * minute, 79, false, 1_600_000),
            BatteryPoint(t0 + 30 * minute, 78, false, 1_576_000),
            BatteryPoint(t0 + 45 * minute, 77, false, 0)
        ), emptyList(), emptyMap(), hour, capacityMah = capacityMah)
        assertEquals(2.6, mixed.totalDrop, 1e-9)
        assertFalse(mixed.usedCounter)
    }

    @Test fun counterToleranceIncludesExactlyThreeExtraPoints() {
        val boundary = counterStep(160_000) // 4 points versus a 1-point percent drop.
        assertEquals(4.0, boundary.totalDrop, 1e-9)
        assertTrue(boundary.usedCounter)

        val beyond = counterStep(160_001) // One uAh beyond the allowed disagreement.
        assertEquals(1.0, beyond.totalDrop, 1e-9)
        assertFalse(beyond.usedCounter)
    }

    @Test fun isolatedPercentGlitchDoesNotBecomeDrain() {
        val points = listOf(BatteryPoint(t0, 80, false),
            BatteryPoint(t0 + 15 * minute, 10, false),
            BatteryPoint(t0 + 30 * minute, 79, false))
        val result = DrainAnalysis.analyze(points, emptyList(), emptyMap(), hour)
        assertEquals(1.0, result.totalDrop, 1e-9)
        assertEquals(0.5, result.screenOffHours, 1e-9)
        assertFalse(result.usedCounter)

        // Invalid readings must be removed and valid ones sorted before checking neighbours.
        val unordered = listOf(points[2], BatteryPoint(t0 + 10 * minute, 101, false), points[0], points[1])
        assertEquals(result, DrainAnalysis.analyze(unordered, emptyList(), emptyMap(), hour))
    }

    @Test fun spikeFilterUsesTheCallersStepLimit() {
        val points = listOf(BatteryPoint(t0, 80, false),
            BatteryPoint(t0 + 90 * minute, 10, false),
            BatteryPoint(t0 + 3 * hour, 79, false))
        // Both neighbours are within 3 h. Default GapLimits (1 h) would miss this spike.
        val result = DrainAnalysis.analyze(points, emptyList(), emptyMap(), 3 * hour)
        assertEquals(1.0, result.totalDrop, 1e-9)
        assertEquals(3.0, result.screenOffHours, 1e-9)
        // With a 1 h limit these observations are gaps, not evidence of drain.
        assertEquals(0.0, DrainAnalysis.analyze(points, emptyList(), emptyMap(), hour).totalDrop, 1e-9)
    }

    private fun event(type: Int, timeMs: Long, className: String? = null) =
        UsageSource.UsageEvent(type, "test.app", className, timeMs)

    @Test fun startupClosesCrashSpansAtLastEventAndLeavesNormalShutdownUnchanged() {
        val last = t0 + 5 * minute
        val startup = t0 + 8 * hour
        val open = listOf(event(SCREEN_INTERACTIVE, t0), event(ACTIVITY_RESUMED, t0, "Activity"),
            event(FOREGROUND_SERVICE_START, t0, "Service"), event(OTHER_EVENT, last))
        val crashed = UsageSource.fromEvents(open + event(DEVICE_STARTUP, startup), startup + hour)
        val expected = listOf(Span(t0, last))
        assertEquals(expected, crashed.screenOn)
        assertEquals(mapOf("test.app" to expected), crashed.apps)
        assertEquals(mapOf("test.app" to expected), crashed.services)
        assertEquals(t0, crashed.coverageStartMs!!)

        val shutdown = open + event(DEVICE_SHUTDOWN, last)
        val shutdownOnly = UsageSource.fromEvents(shutdown, startup + hour)
        assertEquals(crashed, shutdownOnly)
        assertEquals(shutdownOnly,
            UsageSource.fromEvents(shutdown + event(DEVICE_STARTUP, startup), startup + hour))
    }

    @Test fun startupClosesServicesWithoutScreenHistoryAndAllowsNewSessions() {
        val startup = t0 + 8 * hour
        val nextStart = startup + minute
        val end = startup + 5 * minute
        val events = listOf(
            event(DEVICE_STARTUP, t0 - minute), // No previous event or open state.
            event(FOREGROUND_SERVICE_START, t0, "Service"),
            event(OTHER_EVENT, t0 + 5 * minute),
            event(DEVICE_STARTUP, startup),
            event(SCREEN_INTERACTIVE, nextStart),
            event(ACTIVITY_RESUMED, nextStart, "Activity"),
            event(FOREGROUND_SERVICE_START, nextStart, "Service")
        )
        val result = UsageSource.fromEvents(events, end)
        val current = listOf(Span(nextStart, end))
        assertEquals(current, result.screenOn)
        assertEquals(mapOf("test.app" to current), result.apps)
        assertEquals(mapOf("test.app" to listOf(Span(t0, t0 + 5 * minute), Span(nextStart, end))),
            result.services)
        assertEquals(nextStart, result.coverageStartMs!!)
    }

    private companion object {
        const val ACTIVITY_RESUMED = 1
        const val OTHER_EVENT = 5
        const val SCREEN_INTERACTIVE = 15
        const val FOREGROUND_SERVICE_START = 19
        const val DEVICE_SHUTDOWN = 26
        const val DEVICE_STARTUP = 27
    }
}
