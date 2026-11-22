package com.batterylab.personal

import org.junit.Assert.*
import org.junit.Test

class CapacityProxyTest {
    private val day = 86_400_000L
    private val now = 40 * day

    // All fixtures are synthetic; mah is the implied full capacity, not a device measurement.
    private fun reading(daysAgo: Long, mah: Int, pct: Int = 50, charging: Boolean? = false) =
        Reading("phone", now - daysAgo * day, pct, charging, "android", remainingUah = mah * pct * 10)

    private fun halves(early: List<Int>, late: List<Int>) =
        early.mapIndexed { i, mah -> reading(20, mah).copy(timeMs = now - 20 * day + i) } +
            late.mapIndexed { i, mah -> reading(5, mah).copy(timeMs = now - 5 * day + i) }

    @Test fun latestFullReadingSuppliesHeadlineEvenWhileCharging() {
        val result = CapacityProxy.summarize(listOf(
            reading(1, 4600, 100, true), reading(2, 4500, 100), reading(0, 4200),
        ), now)
        assertEquals(4600.0, result.estimateMah!!, 1e-9)
        assertTrue(result.fromFullReading)
        assertEquals(2, result.samples)
        assertEquals(1, result.skippedCharging)
    }

    @Test fun partialReadingsUseMedianAndReportExclusionsAndBand() {
        val result = CapacityProxy.summarize(listOf(
            reading(1, 4000, 20), reading(2, 4200, 50), reading(3, 5000, 90), reading(4, 4400, 60),
            reading(1, 6000, charging = true), reading(1, 7000, pct = 19),
            reading(1, 7000, pct = 10, charging = true), reading(1, 4500).copy(remainingUah = null),
        ), now)
        assertEquals(4300.0, result.estimateMah!!, 1e-9)
        assertFalse(result.fromFullReading)
        assertEquals(4, result.samples)
        assertEquals(2, result.skippedCharging)
        assertEquals(1, result.skippedLowPercent)
        assertEquals(20, result.lowPct)
        assertEquals(90, result.highPct)
        assertEquals(400.0, result.spreadMah!!, 1e-9)
    }

    @Test fun ignoresOldReadingsIncludingFullReadingsAndExclusions() {
        val result = CapacityProxy.summarize(listOf(
            reading(31, 6000, 100), reading(31, 6000, charging = true), reading(31, 6000, 10),
            reading(30, 4200), reading(0, 4400),
        ), now)
        assertEquals(4300.0, result.estimateMah!!, 1e-9)
        assertFalse(result.fromFullReading)
        assertEquals(2, result.samples)
        assertEquals(0, result.skippedCharging)
        assertEquals(0, result.skippedLowPercent)
    }

    @Test fun nineSamplesPerHalfCannotEstablishTrend() {
        val result = CapacityProxy.summarize(halves(List(9) { 5000 }, List(9) { 4000 }), now)
        assertEquals(Trend.NOT_ENOUGH, result.trend)
    }

    @Test fun bothHalvesMustHaveTenSamples() {
        val result = CapacityProxy.summarize(halves(List(9) { 5000 }, List(20) { 4000 }), now)
        assertEquals(Trend.NOT_ENOUGH, result.trend)
    }

    @Test fun equalMediansGiveNoClearChange() {
        val result = CapacityProxy.summarize(halves(List(10) { 4500 }, List(10) { 4500 }), now)
        assertEquals(Trend.NO_CLEAR_CHANGE, result.trend)
    }

    @Test fun clearDropGivesLower() {
        val result = CapacityProxy.summarize(halves(List(10) { 5000 }, List(10) { 4000 }), now)
        assertEquals(Trend.LOWER, result.trend)
        assertEquals(5000.0, result.earlyMah!!, 1e-9)
        assertEquals(4000.0, result.lateMah!!, 1e-9)
    }

    @Test fun clearRiseGivesHigher() {
        val result = CapacityProxy.summarize(halves(List(10) { 4000 }, List(10) { 5000 }), now)
        assertEquals(Trend.HIGHER, result.trend)
    }

    @Test fun differenceMustExceedLargerHalfSpread() {
        // Early IQR is 1,000; the difference is exactly 1,000, so it is not a clear change.
        val early = List(5) { 4000 } + List(5) { 5000 }
        val result = CapacityProxy.summarize(halves(early, List(10) { 3500 }), now)
        assertEquals(Trend.NO_CLEAR_CHANGE, result.trend)
    }

    @Test fun splitUsesWindowMidpointRatherThanSampleCount() {
        val early = List(10) { reading(16, 5000) }
        val late = List(10) { reading(15, 4000) }
        val result = CapacityProxy.summarize(early + late, now)
        assertEquals(Trend.LOWER, result.trend)
        val allEarly = CapacityProxy.summarize(early + late.map { it.copy(timeMs = now - 16 * day) }, now)
        assertEquals(Trend.NOT_ENOUGH, allEarly.trend)
    }

    @Test fun spreadIsNullUnderFourSamples() {
        val result = CapacityProxy.summarize(List(3) { reading(1, 4500) }, now)
        assertNull(result.spreadMah)
    }

    @Test fun emptyWindowHasNoEstimateBandOrSpread() {
        val result = CapacityProxy.summarize(emptyList(), now)
        assertNull(result.estimateMah)
        assertFalse(result.fromFullReading)
        assertEquals(0, result.samples)
        assertNull(result.lowPct)
        assertNull(result.highPct)
        assertNull(result.spreadMah)
        assertNull(result.earlyMah)
        assertNull(result.lateMah)
        assertEquals(Trend.NOT_ENOUGH, result.trend)
    }

    @Test fun unknownChargingAndInvalidCountersFollowExistingImpliedRules() {
        val result = CapacityProxy.summarize(listOf(
            reading(1, 4500, charging = null), reading(1, 0), reading(1, -4500),
        ), now)
        assertEquals(4500.0, result.estimateMah!!, 1e-9)
        assertEquals(1, result.samples)
    }

    @Test fun nonPhoneAndFutureReadingsDoNotEnterSummary() {
        val result = CapacityProxy.summarize(listOf(
            reading(1, 4500), reading(0, 6000, 100).copy(device = "other"), reading(-1, 6000, 100),
        ), now)
        assertEquals(4500.0, result.estimateMah!!, 1e-9)
        assertEquals(1, result.samples)
        assertFalse(result.fromFullReading)
    }
}
