package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixes from the Nov 17 outside review (ChatGPT), findings 3–7. */
class ReviewRound4FixesTest {
    private fun forecast(a2: Double?, a4: Double?) =
        Forecast(1, "phone", 0L, 80, 5.0, 16.0, null, null, ForecastScoring.MODEL_VERSION, a2, a4, null, "PENDING")

    @Test fun rescoreAfterLongGapKeepsSavedHorizons() {
        // A rescore with no readings near the early horizons must not blank them.
        val kept = ForecastScoring.keepScored(forecast(70.0, 61.5), ForecastScore(null, null, null, "CENSORED_GAP"))
        assertEquals(70.0, kept.actual2h!!, 0.0)
        assertEquals(61.5, kept.actual4h!!, 0.0)
        assertNull(kept.actual8h)
        assertEquals("SCORED", kept.status)
    }

    @Test fun rescoreStillFillsNewHorizonsAndStaysPendingUntilDue() {
        val kept = ForecastScoring.keepScored(forecast(70.0, null), ForecastScore(null, 60.0, null, "PENDING"))
        assertEquals(60.0, kept.actual4h!!, 0.0)
        assertEquals("PENDING", kept.status)
        val none = ForecastScoring.keepScored(forecast(null, null), ForecastScore(null, null, null, "CENSORED_CHARGED"))
        assertEquals("CENSORED_CHARGED", none.status)
    }

    @Test fun onlyPhoneJournalEntriesGetADrainVerdict() {
        fun entry(device: String) = JournalEntry(1, 0L, null, device, "x", "t", null)
        assertTrue(UpdateImpactAnalysis.measures(entry("phone")))
        assertFalse(UpdateImpactAnalysis.measures(entry("watch")))
        assertFalse(UpdateImpactAnalysis.measures(entry(BatteryStore.RING_ID)))
    }

    @Test fun zeroRadioTestsBlockManualBleAndTheMonitoringSwitch() {
        assertTrue(ExperimentEndpoint.bleAllowed(null))
        assertTrue(ExperimentEndpoint.bleAllowed("monitoring"))
        for (mode in listOf("baseline", "ring_alone", "ring_with_watch")) assertFalse(ExperimentEndpoint.bleAllowed(mode))
        assertTrue(ExperimentEndpoint.monitoringSwitchAllowed(null))
        for (mode in BatteryStore.EXPERIMENT_MODES) assertFalse(ExperimentEndpoint.monitoringSwitchAllowed(mode))
    }

    @Test fun importCompletesOnlyAnOpenTestWithAValidEnd() {
        assertTrue(ExperimentEndpoint.importCompletes(null, 100L, 200L, 50))
        assertFalse(ExperimentEndpoint.importCompletes(150L, 100L, 200L, 50))   // already finished: keep local
        assertFalse(ExperimentEndpoint.importCompletes(null, 100L, null, null)) // imported copy still open
        assertFalse(ExperimentEndpoint.importCompletes(null, 100L, 90L, 50))    // end before start
    }

    @Test fun csvRoundTripKeepsLeadingApostrophesAndFormulaChars() {
        for (v in listOf("'=literal", "=SUM(A1)", "'plain", "''=x", "-5", "plain")) {
            assertEquals(v, CsvCodec.parseLine(CsvCodec.quote(v)).single())
        }
    }

    @Test fun csvRecordsMaySpanLines() {
        val line = listOf("a", "two\nlines", "c").joinToString(",") { CsvCodec.quote(it) }
        val reader = java.io.BufferedReader(java.io.StringReader(line + "\n" + CsvCodec.quote("next") + "\n"))
        assertEquals(listOf("a", "two\nlines", "c"), CsvCodec.parseLine(CsvCodec.readRecord(reader)!!))
        assertEquals(listOf("next"), CsvCodec.parseLine(CsvCodec.readRecord(reader)!!))
        assertNull(CsvCodec.readRecord(reader))
    }
}
