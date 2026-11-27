package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsEdgeCaseTest {
    private val minuteMs = 60_000L
    private val startMs = 1_000_000_000_000L

    // All readings in these tests are synthetic.
    private fun reading(minute: Long, percent: Int, charging: Boolean = false, tempTenthsC: Int? = null) =
        Reading("phone", startMs + minute * minuteMs, percent, charging, "android", tempTenthsC)

    @Test fun forecastIsScoredAfterAnIsolatedLowGlitch() {
        val forecast = Forecast(
            id = 1, device = "phone", issuedMs = startMs, startPct = 80,
            labRatePerHour = 2.0, labHoursToEmpty = 40.0,
            androidHoursToEmpty = null, androidPersonalized = null,
            modelVersion = ForecastScoring.MODEL_VERSION,
            actual2h = null, actual4h = null, actual8h = null, status = "PENDING")
        // Starts 80 -> 10 -> 79 at 15-minute intervals, then keeps discharging normally.
        val readings = (0..480 step 15).map { minute ->
            reading(minute.toLong(), if (minute == 15) 10 else 80 - minute / 30)
        }

        val score = ForecastScoring.score(forecast, readings, startMs + 600 * minuteMs)

        assertEquals("SCORED", score.status)
        assertEquals(76.0, score.actual2h!!, 1e-9)
        assertEquals(72.0, score.actual4h!!, 1e-9)
        assertEquals(64.0, score.actual8h!!, 1e-9)
    }

    @Test fun chargeTopUsesThePeakBeforeAPluggedInDip() {
        val readings = listOf(
            reading(0, 40, charging = true),
            reading(15, 90, charging = true),
            reading(30, 88, charging = true)
        ).map { it.copy(detail = ReadingDetail(plugged = 1)) }

        val charge = ChargeCurves.observedCharges(readings).single()

        assertEquals(40, charge.fromPct)
        assertEquals(90, charge.toPct)
        assertEquals(startMs + 15 * minuteMs, charge.peakMs)
        assertEquals(startMs + 30 * minuteMs, charge.endMs)
    }

    @Test fun chargeTopIgnoresAOneReadingHighGlitch() {
        val readings = listOf(
            reading(0, 40, charging = true),
            reading(15, 60, charging = true),
            reading(30, 100, charging = true),
            reading(45, 61, charging = true),
            reading(60, 70, charging = true)
        ).map { it.copy(detail = ReadingDetail(plugged = 1)) }

        val charge = ChargeCurves.observedCharges(readings).single()

        assertEquals(70, charge.toPct)
        assertEquals(startMs + 60 * minuteMs, charge.peakMs)
        // The glitch must not complete bands the charge never reached either.
        assertTrue(charge.bands.none { it.toPct > 70 })
    }

    @Test fun chargeGlitchUsesTheCallersGapLimits() {
        // 90-minute checks with a 120-minute step: one run, and the glitch must still be dropped.
        val readings = listOf(40, 60, 100, 61, 70).mapIndexed { i, pct -> reading(i * 90L, pct, charging = true) }
            .map { it.copy(detail = ReadingDetail(plugged = 1)) }

        val charge = ChargeCurves.observedCharges(readings, GapLimits(stepMs = 120 * 60_000L)).single()

        assertEquals(70, charge.toPct)
        assertTrue(charge.bands.none { it.toPct > 70 })
    }

    @Test fun heatMatrixExcludesImplausibleTemperatureButCountsValidHeat() {
        val readings = listOf(
            reading(0, 90, tempTenthsC = 750),
            reading(15, 89, tempTenthsC = 400),
            reading(30, 88, tempTenthsC = 400)
        )

        val matrix = HeatExposure.matrix(readings)

        // Only the 15-minute step starting at 40.0 C belongs in the hot, high-charge cell.
        for (temperatureBand in 0..2) for (chargeBand in 0..2) {
            val expectedHours = if (temperatureBand == 2 && chargeBand == 2) 0.25 else 0.0
            assertEquals(expectedHours, matrix[temperatureBand][chargeBand], 1e-9)
        }
    }
}
