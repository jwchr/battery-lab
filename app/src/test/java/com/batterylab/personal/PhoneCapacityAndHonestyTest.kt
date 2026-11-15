package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCapacityAndHonestyTest {
    private val m = 60_000L
    private val t0 = 1_000_000_000_000L
    private fun r(min: Long, pct: Int, charging: Boolean? = false, uah: Int? = null, current: Int? = null,
                  status: Int? = null, mv: Int? = null) =
        Reading("phone", t0 + min * m, pct, charging, "android", null, mv, uah,
            if (current != null || status != null) ReadingDetail(currentNowRaw = current, status = status) else null)

    /** A clean discharge from [from]% to [to]%, one point per percent every 20 min, at [mah] per 100%. */
    private fun discharge(startMin: Long, from: Int, to: Int, mah: Int) =
        (from downTo to).mapIndexed { i, p -> r(startMin + i * 20L, p, uah = p * mah * 10) }

    @Test fun slopeMethodRecoversCapacity() {
        val est = HonestyAuditor.estimatePhoneCapacity(discharge(0, 95, 40, 4500))
        assertEquals(1, est.slopeSamples)
        assertEquals(4500.0, est.slopeCapacityMah!!, 1.0)
        assertEquals(0.0, est.slopeRmseMah!!, 1e-6)
    }

    @Test fun shortStretchesAreNotUsedForSlope() {
        val est = HonestyAuditor.estimatePhoneCapacity(discharge(0, 60, 40, 4500))   // only 20 points of span
        assertEquals(0, est.slopeSamples)
        assertNull(est.slopeCapacityMah)
    }

    @Test fun saturatedPeakNeedsASettledPlateau() {
        // A 30-minute plateau at 100% counts; a single touch of 100% mid-charge does not.
        val settled = listOf(r(0, 100, true, 4_480_000), r(15, 100, true, 4_490_000), r(30, 100, true, 4_485_000))
        assertEquals(listOf(4490.0), HonestyAuditor.saturatedPeaks(settled, GapLimits()))
        val touch = listOf(r(0, 99, true, 4_400_000), r(5, 100, true, 4_450_000), r(10, 99, false, 4_440_000))
        assertTrue(HonestyAuditor.saturatedPeaks(touch, GapLimits()).isEmpty())
    }

    @Test fun fullStatusCountsAsSaturatedEvenWhenShort() {
        val full = listOf(r(0, 100, true, 4_470_000, status = android.os.BatteryManager.BATTERY_STATUS_FULL))
        assertEquals(listOf(4470.0), HonestyAuditor.saturatedPeaks(full, GapLimits()))
    }

    @Test fun combinedIsMedianOfBothMethods() {
        val readings = listOf(r(0, 100, true, 4_400_000), r(20, 100, true, 4_400_000)) + discharge(40, 95, 40, 4500)
        val est = HonestyAuditor.estimatePhoneCapacity(readings)
        assertEquals(2, est.sampleCount)
        assertEquals((4400.0 + 4500.0) / 2, est.combinedMah!!, 1.0)
    }

    /** Counter falls 125 mAh per 30 min (250 mA) while current is reported as [raw]. */
    private fun currentRun(raw: Int?) = (0..8).map { i -> r(i * 30L, 90 - i, uah = 4_000_000 - i * 125_000, current = raw) }

    @Test fun detectsMicroampsAndMilliamps() {
        assertEquals(1000.0, HonestyAuditor.currentUnitsPerMa(currentRun(-250_000))!!, 0.0)
        assertEquals(1.0, HonestyAuditor.currentUnitsPerMa(currentRun(-250))!!, 0.0)
    }

    @Test fun refusesToGuessUnitsWhenTheyDontFit() {
        assertNull(HonestyAuditor.currentUnitsPerMa(currentRun(null)))
        assertNull(HonestyAuditor.currentUnitsPerMa(currentRun(-12_500)))     // ratio 50: neither µA nor mA
        assertNull(HonestyAuditor.currentUnitsPerMa(currentRun(-250_000).take(4)))  // too few steps
    }

    @Test fun olsAndQuantile() {
        val fit = HonestyAuditor.ols(listOf(1.0, 2.0, 3.0, 4.0), listOf(3.0, 5.0, 7.0, 9.0))!!
        assertEquals(2.0, fit.first, 1e-9)
        assertEquals(1.0, fit.second, 1e-9)
        assertEquals(0.0, fit.third, 1e-9)
        assertNull(HonestyAuditor.ols(listOf(1.0, 1.0), listOf(2.0, 3.0)))
        assertEquals(2.5, HonestyAuditor.quantile(listOf(1.0, 2.0, 3.0, 4.0), 0.5), 1e-9)
        assertEquals(1.0, HonestyAuditor.quantile(listOf(1.0, 2.0, 3.0, 4.0), 0.0), 1e-9)
    }

    @Test fun widgetPairingUsesNearestReadingInWindow() {
        val android = listOf(r(0, 80), r(30, 78), r(60, 76))
        val widget = listOf(r(2, 81), r(31, 78), r(200, 50)).map { it.copy(device = "phone:samsung_widget", source = "samsung_widget") }
        assertEquals(listOf(1, 0), HonestyAuditor.pair(widget, android, 10 * m))
    }

    @Test fun widgetLagIsMinutesUntilTheWidgetCatchesUp() {
        val android = listOf(r(0, 80), r(30, 79))
        val widget = listOf(r(15, 80), r(45, 79)).map { it.copy(source = "samsung_widget") }
        assertEquals(listOf(15.0), HonestyAuditor.widgetLags(android, widget))
    }

    @Test fun auditWithNoDataSaysUnknownInsteadOfGuessing() {
        val a = HonestyAuditor.audit(emptyList(), emptyList(), emptyList(), emptyList(), null)
        assertNull(a.gaugeSkewRatio)
        assertNull(a.bottom15CliffRatio)
        assertNull(a.internalResistanceMilliohms)
        assertNull(a.currentUnitsPerMa)
        assertEquals(0, a.phoneWidgetPairs)
    }

    @Test fun auditOnALinearGaugeShowsNoSkew() {
        val a = HonestyAuditor.audit(discharge(0, 98, 5, 4500), emptyList(), emptyList(), emptyList(), 4500.0)
        assertNotNull(a.midBandMahPerPct)
        a.gaugeSkewRatio?.let { assertEquals(1.0, it, 0.05) }
    }

    @Test fun counterJumpBreaksADischargeRun() {
        val runs = HonestyAuditor.dischargeRuns(listOf(r(0, 80, uah = 3_600_000), r(20, 79, uah = 3_555_000),
            r(40, 78, uah = 3_700_000), r(60, 77, uah = 3_655_000)), GapLimits())
        assertEquals(2, runs.size)
    }
}
