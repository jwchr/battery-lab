package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StatsMainThreadTest {
    private fun source(): String = listOf(
        File("src/main/java/com/batterylab/personal/StatsActivity.kt"),
        File("app/src/main/java/com/batterylab/personal/StatsActivity.kt")
    ).first { it.isFile }.readText()

    private fun outsideLoad(source: String): String {
        val start = source.indexOf("    private fun load(): Loaded {")
        assertTrue("load must be present", start >= 0)
        val end = source.indexOf("\n    private fun ", start + 1)
        assertTrue("load must have an end", end > start)
        return source.removeRange(start, end)
    }

    private fun violations(source: String): List<String> {
        val calls = Regex("""\b(?:HonestyAuditor|ChargeTimes|ChargeCurves|HeatExposure|UpdateImpactAnalysis|Overhead|ForecastScoring|IdleAnalysis|ReadinessPlanner|StatsComparison|StatsCapabilities|CoverageMath|DrainSpreadMath|WeekSplitMath|DrainAnalysis)\s*\.\s*[a-zA-Z_]\w*\s*\(""")
        val math = Regex("""\b(?:CapacityProxy\s*\.\s*summarize|BatteryStats\s*\.\s*(?:device|temperature|fullHoldAsCharging|wattHours)|BatteryMath\s*\.\s*(?:dischargeStretches|overlapping|windowTotals|weeklyRuntime|estimate))\s*\(""")
        return outsideLoad(source).lines().filter {
            Regex("""\bstore\s*\.""").containsMatchIn(it) || calls.containsMatchIn(it) || math.containsMatchIn(it)
        }
    }

    @Test fun analysisAndStoreAccessStayOutOfRenderers() {
        assertEquals(emptyList<String>(), violations(source()))
    }

    @Test fun scanRejectsAStoreReadPutBackInARenderer() {
        val broken = source().replace("    private fun renderTitle() {", "    private fun renderTitle() {\n        store.journal(40)")
        assertTrue(violations(broken).any { it.contains("store.journal") })
    }

    @Test fun scanRejectsEachAnalysisFamily() {
        val calls = listOf("HonestyAuditor.audit()", "ChargeTimes.analyze()", "ChargeCurves.bandMedians()",
            "HeatExposure.matrix()", "UpdateImpactAnalysis.assess()", "Overhead.summarize()",
            "ForecastScoring.reportCard()", "IdleAnalysis.report()", "BatteryStats.temperature()",
            "BatteryMath.dischargeStretches()", "BatteryMath.overlapping()", "BatteryMath.windowTotals()")
        calls.forEach { call ->
            val broken = source().replace("    private fun renderTitle() {", "    private fun renderTitle() {\n        $call")
            assertTrue(call, violations(broken).any { it.contains(call) })
        }
    }

    @Test fun clockAndLabelLookupsStayInLoad() {
        val outside = outsideLoad(source())
        // The one allowed clock read outside load(): the journal Save tap, so the entry keeps the tap time.
        assertEquals(listOf("val tappedAtMs = System.currentTimeMillis()"),
            outside.lines().filter { it.contains("System.currentTimeMillis()") }.map { it.trim().substringBefore(" //") })
        assertTrue(!outside.contains("UsageSource.label("))
        assertTrue(!outside.contains("UsageSource.hiddenCount("))
    }

    @Test fun journalSaveUsesTheTapTime() {
        assertTrue(source().contains("db.addJournal(device, category, title, timeMs = tappedAtMs)"))
    }

    @Test fun aFailedJournalWriteKeepsItsDialogForOnDestroy() {
        val write = source().substringAfter("    private fun writeJournal(").substringBefore("\n    private fun ")
        val failed = write.substringAfter("is SectionResult.Failed ->")
        val beforeOutcome = write.substringBefore("when (result)")
        assertTrue("The success path must forget the closed dialog", write.substringAfter("is SectionResult.Ok ->").substringBefore("is SectionResult.Failed").contains("journalDialog = null"))
        assertTrue("Only the success path may forget the dialog", !beforeOutcome.substringAfter("Background.onMain").contains("journalDialog = null") && !failed.contains("journalDialog = null"))
    }
}
