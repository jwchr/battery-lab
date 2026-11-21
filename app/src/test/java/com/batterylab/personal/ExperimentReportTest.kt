package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentReportTest {
    private val hour = 3_600_000L

    // All runs and percentages in this file are synthetic fixtures.
    private fun run(
        startHour: Long,
        mode: String = "baseline",
        rate: Double? = 1.0,
        hours: Long = 2,
        startPct: Int = 80,
        quarantine: String? = null,
        finished: Boolean = true,
    ) = ExperimentResult(
        Experiment(startHour, "test", mode, startHour * hour, startPct,
            if (finished) (startHour + hours) * hour else null, if (finished) startPct - 4 else null),
        rate, null, 0, quarantine,
    )

    @Test fun armSummarizesAcceptedRatesAndFinishedSetAsideRuns() {
        val results = listOf(
            run(0, rate = 1.0, hours = 2, startPct = 70),
            run(4, rate = 2.0, hours = 4, startPct = 80),
            run(10, rate = 4.0, hours = 6, startPct = 90),
            run(20, rate = 99.0, hours = 12, quarantine = "Synthetic exclusion"),
            run(40, rate = null, quarantine = "Still running", finished = false),
            run(50, mode = "monitoring", rate = 9.0),
        )
        val arm = armDetail(results.reversed(), "baseline")
        assertEquals(3, arm.runs)
        assertEquals(1, arm.setAside)
        assertEquals(2.0, arm.medianRate!!, 1e-9)
        assertEquals(1.0, arm.minRate!!, 1e-9)
        assertEquals(4.0, arm.maxRate!!, 1e-9)
        assertEquals(4.0, arm.meanHours!!, 1e-9)
        assertEquals(80.0, arm.meanStartPct!!, 1e-9)
    }

    @Test fun fittedRateIsUsedJustAsInTheExistingComparison() {
        val result = run(0, rate = 8.0).copy(regressionRate = 2.0)
        assertEquals(2.0, armDetail(listOf(result), "baseline").medianRate!!, 1e-9)
    }

    @Test fun noCountedRunsHaveNoSummaryValues() {
        val arm = armDetail(listOf(run(0, quarantine = "Synthetic exclusion")), "baseline")
        assertEquals(0, arm.runs)
        assertEquals(1, arm.setAside)
        assertNull(arm.medianRate)
        assertNull(arm.minRate)
        assertNull(arm.maxRate)
        assertNull(arm.meanHours)
        assertNull(arm.meanStartPct)
    }

    private fun sequence(vararg modes: String) = modes.mapIndexed { i, mode -> run(i * 4L, mode) }

    @Test fun orderSortsCountedRunsBeforeCheckingAlternation() {
        val results = sequence("baseline", "monitoring", "baseline", "monitoring")
        assertEquals(OrderCheck.ALTERNATED, order(results.reversed(), "baseline", "monitoring"))
        assertEquals(OrderCheck.ALTERNATED,
            order(sequence("baseline", "monitoring", "monitoring", "baseline"), "baseline", "monitoring"))
    }

    @Test fun orderDetectsBothDirectionsOfBlockedRuns() {
        for (results in listOf(sequence("baseline", "baseline", "monitoring", "monitoring"),
            sequence("monitoring", "monitoring", "baseline", "baseline"))) {
            // An excluded run and an unrelated mode must not create apparent alternation.
            val extras = listOf(run(2, "monitoring", quarantine = "Synthetic exclusion"), run(3, "ring_alone"))
            assertEquals(OrderCheck.BLOCKED, order((results + extras).reversed(), "baseline", "monitoring"))
        }
    }

    @Test fun orderNeedsAtLeastTwoCountedRunsOfEachArm() {
        val results = sequence("baseline", "monitoring", "monitoring") +
            run(20, "baseline", quarantine = "Synthetic exclusion")
        assertEquals(OrderCheck.TOO_FEW, order(results, "baseline", "monitoring"))
        assertEquals(OrderCheck.TOO_FEW, order(emptyList(), "baseline", "monitoring"))
    }

    private fun arm(mode: String, hours: Long = 2, startPct: Int = 80) =
        armDetail(listOf(run(0, mode, hours = hours, startPct = startPct)), mode)

    @Test fun similarConditionsGiveNoWarnings() {
        assertTrue(conditionWarnings(arm("baseline"), arm("monitoring")).isEmpty())
        assertTrue(conditionWarnings(arm("baseline", hours = 2, startPct = 80),
            arm("monitoring", hours = 3, startPct = 65)).isEmpty())
    }

    @Test fun muchLongerRunsWarnWithTheCorrectArmNames() {
        val a = arm("baseline", hours = 2)
        val b = arm("monitoring", hours = 6)
        val expected = "Monitored runs were much longer than Baseline runs; " +
            "drain is not always steady, so compare like with like."
        assertEquals(listOf(expected), conditionWarnings(a, b))
        assertEquals(listOf(expected), conditionWarnings(b, a))
    }

    @Test fun DifferentStartingChargeWarns() {
        val a = arm("baseline", startPct = 95)
        val b = arm("monitoring", startPct = 60)
        val expected = "Baseline runs started at a much higher charge than Monitored runs; " +
            "compare runs starting near the same charge."
        assertEquals(listOf(expected), conditionWarnings(a, b))
        assertEquals(listOf(expected), conditionWarnings(b, a))
    }

    @Test fun warningsCanCoexistAndMissingConditionsAreSkipped() {
        assertEquals(2, conditionWarnings(arm("baseline", 2, 95), arm("monitoring", 6, 60)).size)
        val empty = armDetail(emptyList(), "baseline")
        assertTrue(conditionWarnings(empty, arm("monitoring")).isEmpty())
    }

    @Test fun sourceMatchesTheIntendedArmSeries() {
        // Endpoint.choose may accept widget/manual endpoints in monitoring; usesSource excludes
        // those from its fitted series. Other arms use the widget, including manual widget entries.
        assertTrue(ExperimentStats.usesSource("monitoring", "ring_ble"))
        assertTrue(!ExperimentStats.usesSource("monitoring", "manual_widget"))
        assertTrue(ExperimentStats.usesSource("baseline", "manual_widget"))
        assertTrue(!ExperimentStats.usesSource("baseline", "ring_ble"))
        assertEquals("direct Ring reads", arm("monitoring").source)
        for (mode in listOf("baseline", "ring_alone", "ring_with_watch")) {
            assertEquals("Samsung's widget", arm(mode).source)
        }
    }
}
