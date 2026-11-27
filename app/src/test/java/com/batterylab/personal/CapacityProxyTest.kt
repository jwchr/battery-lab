package com.batterylab.personal

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

class CapacityProxyTest {
    private val day = 86_400_000L
    private val now = 40 * day
    private fun formatMah(mah: Double) = String.format(Locale.US, "%,d", mah.roundToInt())

    @Test fun capacityCaptionKeepsRatedMinimumWithAdvertisedCapacity() {
        assertEquals(
            "Roughly 95% of the 4,500 mAh advertised (4,400 mAh rated minimum). " +
                "This is a charge-counter estimate, not a battery health test.",
            CapacityProxy.capacityCaption(95, PhoneSpec(4500.0, 4400.0), ::formatMah),
        )
    }

    @Test fun capacityCaptionWithoutPercentStillIncludesRatedMinimum() {
        assertEquals("Advertised: 4,500 mAh (4,400 mAh rated minimum).",
            CapacityProxy.capacityCaption(null, PhoneSpec(4500.0, 4400.0), ::formatMah))
    }

    @Test fun capacityCaptionWithoutSpecRemainsEmpty() {
        assertEquals("", CapacityProxy.capacityCaption(null, null, ::formatMah))
    }

    @Test fun fullReadingCaptionCountsOtherReadingsAndKeepsSpread() {
        val estimate = CapacityProxy.summarize(listOf(
            reading(0, 4500, 100, true),
            reading(1, 4000, 20), reading(2, 4200, 50), reading(3, 5000, 90), reading(4, 4400, 60),
            reading(1, 4500, 10),
        ), now)
        assertEquals(
            "From the latest 100% reading; 4 other readings between 20% and 90%, not charging " +
                "(1 skipped while charging, 1 below 20%). Spread ±200 mAh.",
            CapacityProxy.readingsCaption(estimate, ::formatMah),
        )
    }

    @Test fun fullReadingCaptionWithoutOtherReadingsOmitsPartialSampleClaim() {
        val estimate = CapacityProxy.summarize(listOf(
            reading(0, 4500, 100, true), reading(1, 4500, 10),
        ), now)
        assertEquals(
            "From the latest 100% reading (1 skipped while charging, 1 below 20%).",
            CapacityProxy.readingsCaption(estimate, ::formatMah),
        )
    }

    @Test fun partialReadingCaptionStaysUnchanged() {
        val estimate = CapacityProxy.summarize(listOf(reading(1, 4500, 30), reading(2, 4500, 70)), now)
        assertEquals("From 2 readings between 30% and 70%, not charging (0 skipped while charging, 0 below 20%).",
            CapacityProxy.readingsCaption(estimate, ::formatMah))
    }

    @Test fun singleNonChargingFullReadingHasNoOtherReadings() {
        val estimate = CapacityProxy.summarize(listOf(reading(0, 4500, 100)), now)
        assertEquals(1, estimate.samples)
        assertEquals(0, estimate.otherSamples)
        assertNull(estimate.otherLowPct)
        assertNull(estimate.otherHighPct)
        assertEquals("From the latest 100% reading (0 skipped while charging, 0 below 20%).",
            CapacityProxy.readingsCaption(estimate, ::formatMah))
    }

    @Test fun nonChargingFullReadingIsExcludedFromOtherCountAndRange() {
        val estimate = CapacityProxy.summarize(listOf(
            reading(0, 4500, 100), reading(1, 4200, 20), reading(2, 4400, 70),
        ), now)
        assertEquals(4500.0, estimate.estimateMah!!, 1e-9)
        assertEquals(3, estimate.samples)
        assertEquals(2, estimate.otherSamples)
        assertEquals("From the latest 100% reading; 2 other readings between 20% and 70%, not charging " +
            "(0 skipped while charging, 0 below 20%).",
            CapacityProxy.readingsCaption(estimate, ::formatMah))
    }

    @Test fun fullReadingCaptionUsesSingularForOneOtherReading() {
        val estimate = CapacityProxy.summarize(listOf(
            reading(0, 4500, 100), reading(1, 4200, 80),
        ), now)
        assertEquals("From the latest 100% reading; 1 other reading between 80% and 80%, not charging " +
            "(0 skipped while charging, 0 below 20%).",
            CapacityProxy.readingsCaption(estimate, ::formatMah))
    }

    @Test fun nonPositiveCountersAreNotCountedAsSkipped() {
        val estimate = CapacityProxy.summarize(listOf(
            reading(1, 0, charging = true), reading(1, -4500, charging = true),
            reading(1, 0, 10), reading(1, -4500, 10),
            reading(1, 4500, charging = true), reading(1, 4500, 10),
        ), now)
        assertNull(estimate.estimateMah)
        assertEquals(0, estimate.samples)
        assertEquals(1, estimate.skippedCharging)
        assertEquals(1, estimate.skippedLowPercent)
    }

    @Test fun emptyReadingCaptionStaysUnchanged() {
        val estimate = CapacityProxy.summarize(emptyList(), now)
        assertEquals("From 0 readings, not charging (0 skipped while charging, 0 below 20%).",
            CapacityProxy.readingsCaption(estimate, ::formatMah))
    }

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

    /** One value per day, with [perDay] polls on each day, early half 16+ days ago, late half under 15. */
    private fun days(early: List<Int>, late: List<Int>, perDay: Int = 3) =
        early.flatMapIndexed { d, mah -> List(perDay) { p -> reading(16L + d, mah).copy(timeMs = now - (16L + d) * day + p) } } +
            late.flatMapIndexed { d, mah -> List(perDay) { p -> reading(1L + d, mah).copy(timeMs = now - (1L + d) * day + p) } }

    @Test fun theDayHoldingTheMidpointCountsInOneHalfOnly() {
        // Hourly polls over 7 calendar days with the midpoint at 14:00 on the fourth: that day goes
        // to the later half, so the earlier half has 3 days, not 4.
        val at = now + 14 * 3_600_000L
        val mid = at - 15 * day
        val polls = (0 until 7 * 24).map { hr ->
            val t = (25L - 3) * day + hr * 3_600_000L
            reading(0, if (t < mid) 5000 else 4000).copy(timeMs = t)
        }
        assertEquals(Trend.NOT_ENOUGH, CapacityProxy.summarize(polls, at).trend)
        // One more earlier day gives each half 4 days, and the drop is named.
        val more = (0 until 24).map { hr -> reading(0, 5000).copy(timeMs = (25L - 4) * day + hr * 3_600_000L) }
        assertEquals(Trend.LOWER, CapacityProxy.summarize(more + polls, at).trend)
    }

    @Test fun threeDaysInAHalfCannotEstablishTrend() {
        val result = CapacityProxy.summarize(days(List(3) { 5000 }, List(4) { 4000 }), now)
        assertEquals(Trend.NOT_ENOUGH, result.trend)
    }

    @Test fun manyPollsOnOneDayCountAsOneDay() {
        // 40 polls a half used to pass the old 10-sample rule; they are one day each.
        val result = CapacityProxy.summarize(halves(List(40) { 5000 }, List(40) { 4000 }), now)
        assertEquals(Trend.NOT_ENOUGH, result.trend)
    }

    @Test fun equalMediansGiveNoClearChange() {
        val result = CapacityProxy.summarize(days(List(4) { 4500 }, List(4) { 4500 }), now)
        assertEquals(Trend.NO_CLEAR_CHANGE, result.trend)
    }

    @Test fun aFlatCounterWithASmallShiftIsNoClearChange() {
        // Both halves have zero spread; a 2 percent shift is within counter drift.
        val result = CapacityProxy.summarize(days(List(4) { 4500 }, List(4) { 4590 }), now)
        assertEquals(Trend.NO_CLEAR_CHANGE, result.trend)
        val drop = CapacityProxy.summarize(days(List(4) { 4500 }, List(4) { 4410 }), now)
        assertEquals(Trend.NO_CLEAR_CHANGE, drop.trend)
    }

    @Test fun aChangeJustOverThreePercentOfAFlatCounterCounts() {
        val result = CapacityProxy.summarize(days(List(4) { 5000 }, List(4) { 4849 }), now)
        assertEquals(Trend.LOWER, result.trend)
        val edge = CapacityProxy.summarize(days(List(4) { 5000 }, List(4) { 4850 }), now)
        assertEquals("exactly 3 percent is not a clear change", Trend.NO_CLEAR_CHANGE, edge.trend)
    }

    @Test fun clearDropGivesLower() {
        val result = CapacityProxy.summarize(days(List(4) { 5000 }, List(4) { 4000 }), now)
        assertEquals(Trend.LOWER, result.trend)
        assertEquals(5000.0, result.earlyMah!!, 1e-9)
        assertEquals(4000.0, result.lateMah!!, 1e-9)
    }

    @Test fun clearRiseGivesHigher() {
        val result = CapacityProxy.summarize(days(List(4) { 4000 }, List(4) { 5000 }), now)
        assertEquals(Trend.HIGHER, result.trend)
    }

    @Test fun differenceMustExceedLargerHalfSpread() {
        // Early daily IQR is 1,000; the difference is exactly 1,000, so it is not a clear change.
        val result = CapacityProxy.summarize(days(listOf(4000, 4000, 5000, 5000), List(4) { 3500 }), now)
        assertEquals(Trend.NO_CLEAR_CHANGE, result.trend)
    }

    @Test fun oneDaysPollsCountOnceInTheMedian() {
        // A busy day of 30 polls at 4,000 no longer outweighs three quiet days at 5,000.
        val early = days(List(4) { 5000 }, emptyList(), perDay = 1) +
            List(30) { p -> reading(20, 4000).copy(timeMs = now - 20 * day + 1000 + p) }
        val result = CapacityProxy.summarize(early + days(emptyList(), List(4) { 5000 }), now)
        assertEquals(5000.0, result.earlyMah!!, 1e-9)
    }

    @Test fun splitUsesWindowMidpointRatherThanSampleCount() {
        val result = CapacityProxy.summarize(days(List(4) { 5000 }, List(4) { 4000 }), now)
        assertEquals(Trend.LOWER, result.trend)
        val allEarly = CapacityProxy.summarize(days(List(8) { 5000 }, emptyList()), now)
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
