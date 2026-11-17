package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainAndStatsRegressionTest {
    private val m = 60_000L
    private val h = 60 * m
    private val t0 = 1_000_000_000_000L
    private fun r(min: Long, pct: Int, charging: Boolean? = false, source: String = "android", detail: ReadingDetail? = null,
                  uah: Int? = null, temp: Int? = null) =
        Reading("phone", t0 + min * m, pct, charging, source, temp, null, uah, detail)

    @Test fun csvRoundTripsAwkwardText() {
        val values = listOf("plain", "with, comma", "with \"quotes\"", "=SUM(A1)", "-5", "", "+vpn")
        val line = values.joinToString(",") { CsvCodec.quote(it) }
        assertEquals(values, CsvCodec.parseLine(line))
    }

    @Test fun csvDefusesSpreadsheetFormulas() {
        assertTrue(CsvCodec.quote("=cmd()").startsWith("\"'="))
        assertEquals("\"ok\"", CsvCodec.quote("ok"))
        assertEquals(listOf("'not a formula"), CsvCodec.parseLine("\"'not a formula\""))
    }

    @Test fun csvParsesUtcTimestamps() {
        assertEquals(0L, CsvCodec.parseUtc("1970-01-01T00:00:00.000Z"))
        assertNull(CsvCodec.parseUtc("yesterday"))
    }

    @Test fun qualityFlagsMarkRepeatsWidgetAgeAndCounterJumps() {
        val a = r(0, 50, uah = 2_000_000)
        assertTrue(QualityFlags.has(QualityFlags.assess(a, r(15, 50, uah = 1_990_000)), QualityFlags.UNCHANGED_REPEAT))
        assertTrue(QualityFlags.has(QualityFlags.assess(a, r(15, 49, uah = 2_100_000)), QualityFlags.COUNTER_RESET_OR_JUMP))
        assertFalse(QualityFlags.has(QualityFlags.assess(a, r(15, 49, true, uah = 2_100_000)), QualityFlags.COUNTER_RESET_OR_JUMP))
        val w = QualityFlags.assess(null, r(0, 50, source = SamsungWidgetSource.SOURCE))
        assertTrue(QualityFlags.has(w, QualityFlags.UNKNOWN_SOURCE_AGE))
        assertFalse(QualityFlags.has(w, QualityFlags.UNCHANGED_REPEAT))
    }

    @Test fun qualityFlagsCatchRebootsAndClockJumps() {
        val a = r(0, 50, detail = ReadingDetail(elapsedMs = 1_000_000, bootCount = 3))
        assertTrue(QualityFlags.has(QualityFlags.assess(a, r(15, 49, detail = ReadingDetail(elapsedMs = 10, bootCount = 4))),
            QualityFlags.CLOCK_OR_BOOT_DISCONTINUITY))
        assertTrue(QualityFlags.has(QualityFlags.assess(a, r(15, 49, detail = ReadingDetail(elapsedMs = 1_000_000 + 2 * m, bootCount = 3))),
            QualityFlags.CLOCK_OR_BOOT_DISCONTINUITY))
        assertFalse(QualityFlags.has(QualityFlags.assess(a, r(15, 49, detail = ReadingDetail(elapsedMs = 1_000_000 + 15 * m, bootCount = 3))),
            QualityFlags.CLOCK_OR_BOOT_DISCONTINUITY))
    }

    @Test fun qualityFlagsKeepWhatTheCallerSet() {
        val cur = r(0, 50, detail = ReadingDetail(qualityFlags = QualityFlags.AMBIGUOUS_WIDGET_SLOT))
        assertTrue(QualityFlags.has(QualityFlags.assess(null, cur), QualityFlags.AMBIGUOUS_WIDGET_SLOT))
    }

    @Test fun spanIndexMeasuresOverlap() {
        val idx = SpanIndex(listOf(Span(10, 20), Span(30, 50), Span(60, 60)))
        assertEquals(30L, idx.overlap(0, 100))
        assertEquals(5L, idx.overlap(15, 25))
        assertEquals(15L, idx.overlap(15, 40))
        assertEquals(0L, idx.overlap(20, 30))
        assertEquals(0L, idx.overlap(40, 35))
    }

    @Test fun historicalRateWeightsByTime() {
        val s = listOf(DischargeStretch(0, 2 * h, 80, 70), DischargeStretch(3 * h, 11 * h, 70, 54))
        assertEquals(26.0 / 10.0, BatteryMath.historicalRate(s)!!, 1e-9)
        assertNull(BatteryMath.historicalRate(listOf(DischargeStretch(0, 2 * h, 80, 78))))
        assertNull(BatteryMath.historicalRate(emptyList()))
    }

    @Test fun mergeWatchPrefersDirectReadings() {
        val direct = Reading("watch:x", t0, 60, false, "wear_os")
        val widgetNear = Reading("watch:samsung_widget", t0 + 2 * m, 61, false, SamsungWidgetSource.SOURCE)
        val widgetFar = Reading("watch:samsung_widget", t0 + 30 * m, 58, false, SamsungWidgetSource.SOURCE)
        assertEquals(listOf(direct, widgetFar), BatteryStore.mergeWatch(listOf(widgetFar, widgetNear, direct)))
    }

    @Test fun drainSplitsIdleAndAppsAndUsesCounterWhenCapacityKnown() {
        // 4 h screen off losing 1%/h, then 2 h with one app on screen losing 6%/h.
        val pts = (0..4).map { BatteryPoint(t0 + it * h, 90 - it, false, (90 - it) * 45_000) } +
            (1..2).map { BatteryPoint(t0 + (4 + it) * h, 86 - 6 * it, false, (86 - 6 * it) * 45_000) }
        val screen = listOf(Span(t0 + 4 * h, t0 + 6 * h))
        val apps = mapOf("app.a" to screen)
        val d = DrainAnalysis.analyze(pts, screen, apps, 2 * h, capacityMah = 4500.0)
        assertTrue(d.usedCounter)
        assertEquals(1.0, d.screenOffRate!!, 0.01)
        assertEquals(16.0, d.totalDrop, 0.01)
        val a = d.apps.single { it.app == "app.a" }
        assertEquals(2.0, a.foregroundHours, 1e-9)
        assertEquals(10.0, a.percent, 0.01)           // 12 lost on screen, minus 2 h of idle
        assertEquals(450.0, a.mahUsed!!, 1.0)
    }

    @Test fun heatMatrixBucketsTimeByTemperatureAndCharge() {
        val readings = listOf(r(0, 90, temp = 400), r(30, 89, temp = 250), r(60, 10, temp = 250), r(90, 9, temp = 250))
        val mtx = HeatExposure.matrix(readings)
        assertEquals(0.5, mtx[2][2], 1e-9)   // >38°C at 80–100%
        assertEquals(0.5, mtx[0][2], 1e-9)   // <30°C at 89%
        assertEquals(0.5, mtx[0][0], 1e-9)   // <30°C below 20%
    }

    @Test fun observedChargeTimesBandsAndTopUp() {
        val run = (0..80).map { i -> r(i.toLong(), 20 + i, true, detail = ReadingDetail(plugged = 1)) }  // 1%/min
        val charges = ChargeCurves.observedCharges(run)
        assertEquals(1, charges.size)
        assertEquals(1, charges[0].pluggedType)
        assertEquals(30.0, ChargeCurves.bandMedians(charges)[20 to 50]!!, 1e-9)
        assertEquals(15.0, ChargeCurves.topUpPercent(charges)!!, 1e-9)
    }

    @Test fun idleStretchesAndUnusualNights() {
        val nights = (0 until 6).flatMap { n ->
            val rate = if (n == 5) 4 else 1
            (0..6).map { i -> r(n * 24 * 60L + i * 60L, 90 - i * rate) }
        }
        val st = IdleAnalysis.stretches(nights, emptyList())
        assertEquals(6, st.size)
        val rep = IdleAnalysis.report(st)
        assertEquals(1.0, rep.medianRate!!, 1e-9)
        assertEquals(1, rep.unusual.size)
        assertEquals(4.0, rep.unusual[0].rate, 1e-9)
    }
}
