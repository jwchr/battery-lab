package com.batterylab.personal

import org.junit.Assert.*
import org.junit.Test

class HomeHistoryTest {
    private val hour = 3_600_000L
    private val start = 1_700_000_000_000L
    private val gaps = GapLimits(2 * hour, 8 * hour)
    private fun reading(h: Int, pct: Int, charging: Boolean = false, source: String = "android") =
        Reading("phone", start + h * hour, pct, charging, source, remainingUah = pct * 40_000)
    private fun weekOf(ms: Long) = start + ((ms - start) / (7 * 24 * hour)) * (7 * 24 * hour)

    @Test fun batteryLifeMatchesOldInlineAnalysisAndKnownRuntime() {
        val history = (0..4).map { reading(it, 80 - it * 5) }
        val actual = HomeHistory.life(history, gaps, start, ::weekOf)
        val normalized = BatteryStats.fullHoldAsCharging(history)
        val oldStretches = BatteryMath.dischargeStretches(normalized.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = gaps)
        assertEquals(oldStretches, actual.stretches)
        assertEquals(BatteryMath.weeklyRuntime(oldStretches, ::weekOf).takeLast(6), actual.weeks)
        assertEquals(1, actual.stretches.size)
        // Synthetic 20 percentage points in 4 hours => 20 hours per full charge.
        assertEquals(20.0, actual.stretches.single().fullChargeHours, 1e-9)
        assertEquals(4000.0, actual.capacityMah!!, 1e-9)
        assertEquals(BatteryMath.median(normalized.mapNotNull {
            BatteryMath.impliedCapacityMah(it.percent, it.remainingUah, it.charging)
        }), actual.capacityMah)
    }

    @Test fun weeklyCapacityExcludesOlderAndChargingReadings() {
        val history = listOf(reading(0, 80).copy(remainingUah = 80 * 60_000),
            reading(168, 80), reading(169, 70, true).copy(remainingUah = 70 * 80_000))
        assertEquals(4000.0, HomeHistory.life(history, gaps, start + 168 * hour, ::weekOf).capacityMah!!, 1e-9)
    }

    @Test fun lifeKeepsOnlySixWeeksAndHonorsGapLimits() {
        val history = (0..7).flatMap { week -> (0..4).map { reading(week * 168 + it, 80 - it * 5) } }
        val actual = HomeHistory.life(history, gaps, start, ::weekOf)
        assertEquals(8, actual.stretches.size)
        assertEquals(6, actual.weeks.size)
        assertEquals(start + 2 * 168 * hour, actual.weeks.first().weekStartMs)
        val separated = listOf(reading(0, 80), reading(1, 75), reading(12, 20), reading(13, 15))
        assertEquals(BatteryMath.dischargeStretches(separated.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = gaps),
            HomeHistory.life(separated, gaps, start, ::weekOf).stretches)
        assertTrue(HomeHistory.life(separated, gaps, start, ::weekOf).stretches.all { it.hours <= 1.0 })
    }

    @Test fun deviceBoardMatchesInlineProjectionAndFreshnessBoundary() {
        val history = (0..4).map { reading(it, 80 - it * 5) }
        val last = history.last()
        val other = last.copy(source = "samsung_widget", percent = 61)
        val actual = HomeHistory.board(history, other, last.timeMs + hour, hour, false, gaps)
        assertEquals(last, actual.last)
        assertTrue(actual.fresh)
        assertEquals(BatteryMath.estimate(history.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps), actual.projection)
        assertNotNull(actual.projection)
        assertEquals(5.0, actual.projection!!.percentPerHour, 1e-9)
        assertEquals(other, actual.other)
        val stale = HomeHistory.board(history, other, last.timeMs + hour + 1, hour, false, gaps)
        assertFalse(stale.fresh)
        assertNull(stale.projection)
        assertNull(stale.other)
        val dropped = HomeHistory.board(history.map { it.copy(source = "samsung_widget") }, other, last.timeMs, hour, true, gaps)
        assertFalse(dropped.fresh)
    }

    @Test fun otherSourceMustDisagreeWithinThirtyMinutesAndBeExplicitlySelected() {
        val history = listOf(reading(0, 80))
        val last = history.single()
        fun board(other: Reading?) = HomeHistory.board(history, other, last.timeMs, hour, false, gaps)
        assertNull(board(null).other)
        assertNull(board(last.copy(source = "samsung_widget")).other)
        assertNotNull(board(last.copy(timeMs = last.timeMs - 30 * 60_000, percent = 79)).other)
        assertNull(board(last.copy(timeMs = last.timeMs - 30 * 60_000 - 1, percent = 79)).other)
    }

    @Test fun fullHoldAnalysisDoesNotChangeTheShownReading() {
        val history = listOf(reading(0, 100, true, "samsung_widget"), reading(1, 100, false, "samsung_widget"))
        val actual = HomeHistory.board(history, null, history.last().timeMs, hour, false, gaps)
        assertEquals(true, actual.recent.last().charging)
        assertEquals(false, actual.last!!.charging)
        assertEquals(DeviceCard.FULL, DeviceCard.status(actual.last, actual.fresh))
    }

    @Test fun shortCurrentStretchUsesTheSameHistoricalFallback() {
        val history = listOf(reading(0, 80), reading(1, 75), reading(2, 70), reading(3, 100, true), reading(4, 80), reading(5, 79))
        val last = history.last()
        val actual = HomeHistory.board(history, null, last.timeMs, hour, false, gaps)
        assertNull(actual.projection)
        val old = BatteryMath.historicalRate(BatteryMath.dischargeStretches(history.map {
            BatteryPoint(it.timeMs, it.percent, it.charging)
        }, gaps = gaps).filter { it.endMs < last.timeMs })
        assertNotNull(old)
        assertEquals(old, actual.historyRate)
    }

    @Test fun experimentsPreserveInclusiveWindowsOrderAndQuarantine() {
        val run = Experiment(1, "sample", "baseline", start, 90, start + 12 * hour, 78)
        val next = run.copy(id = 2, startMs = start + 12 * hour, startPct = 78, endMs = start + 24 * hour, endPct = 66)
        val readings = (0..24).map { reading(it, 90 - it, source = "manual_widget") } +
            listOf(reading(-1, 95, true), reading(25, 60, true))
        val runs = listOf(run, next, next.copy(id = 3, endMs = null, endPct = null))
        val actual = HomeHistory.experiments(runs, readings)
        assertEquals(runs.map { e -> ExperimentStats.result(e, readings.filter {
            it.timeMs >= e.startMs && it.timeMs <= (e.endMs ?: Long.MAX_VALUE)
        }) }, actual)
        assertEquals(1.0, actual[0].rate!!, 1e-9)
        assertEquals(1.0, actual[1].rate!!, 1e-9)
        assertEquals("Still running", actual[2].quarantine)
        val charged = HomeHistory.experiments(listOf(run), readings + reading(6, 84, true)).single()
        assertEquals("Charged during the run", charged.quarantine)
    }

    @Test fun emptyHistoryHasNoInventedValues() {
        val life = HomeHistory.life(emptyList(), gaps, start, ::weekOf)
        assertTrue(life.stretches.isEmpty()); assertTrue(life.weeks.isEmpty()); assertNull(life.capacityMah)
        val board = HomeHistory.board(emptyList(), null, start, hour, false, gaps)
        assertNull(board.last); assertFalse(board.fresh); assertNull(board.projection); assertNull(board.historyRate)
        assertTrue(HomeHistory.experiments(emptyList(), emptyList()).isEmpty())
    }
}
