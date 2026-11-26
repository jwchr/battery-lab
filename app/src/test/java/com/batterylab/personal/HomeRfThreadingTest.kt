package com.batterylab.personal

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression tripwires for render I/O boundaries; numerical outcomes are tested separately. */
class HomeRfThreadingTest {
    private fun source(relative: String): String = listOf(File("src/main/java/$relative"), File("app/src/main/java/$relative"))
        .first { it.isFile }.readText()
    private fun home() = source("com/batterylab/personal/MainActivity.kt")
    private fun rf() = source("com/batterylab/personal/rf/RfLabActivity.kt")
    private fun method(source: String, name: String): String {
        val start = source.indexOf("    private fun $name(")
        assertTrue("Missing $name", start >= 0)
        return source.substring(start, source.indexOf("\n    private fun ", start + 1).let { if (it < 0) source.length else it })
    }
    private fun historyReads(source: String): List<String> {
        val query = Regex("""\bstore\s*\.\s*(?:since|recent|latest|primaryWatchId|companionWatchReadings|lastProbeEvent|experiments)\s*\(""")
        return listOf("render", "show", "deviceBoard", "renderBatteryLifeSection", "renderWidgetSection", "slotRow", "intervalPill")
            .flatMap { name -> method(source, name).lines().filter { query.containsMatchIn(it) } }
    }

    @Test fun homeViewBuildingHasNoHistoryQueries() {
        assertEquals(emptyList<String>(), historyReads(home()))
        // These two queries deliberately run when tapped, to guard actions with live experiment state.
        assertEquals(2, Regex("store\\.activeExperiment\\(").findAll(method(home(), "show")).count())
    }

    @Test fun queryGuardDetectsAnAccidentalMainThreadRead() {
        val broken = home().replace("        val devices = l.devices", "        val devices = l.devices\n        store.since(\"phone\", 0)")
        assertTrue(historyReads(broken).any { it.contains("store.since") })
    }

    @Test fun homeRetainsViewsDuringRefreshAndRejectsOldResults() {
        val render = method(home(), "render")
        assertTrue(render.contains("if (body.childCount == 0)"))
        assertFalse(render.substringBefore("Background.onMain").contains("removeAllViews"))
        assertTrue(render.indexOf("load(widget, includeLab)") > render.indexOf("Background.io.execute"))
        assertEquals(2, Regex("isDestroyed \\|\\| isFinishing \\|\\| generation != renderGeneration").findAll(render).count())
        assertTrue(render.contains("Couldn't read the stored history. Try again in a moment."))
        assertTrue(home().substringAfter("override fun onDestroy()").contains("++renderGeneration"))
        assertTrue(method(home(), "logRender").contains("ms (background), views"))
    }

    @Test fun rfListUsesOnlySnapshotOnMainAndInvalidatesEveryParentRender() {
        val render = method(rf(), "renderSessions")
        assertTrue(render.indexOf("SavedSessions.load(") > render.indexOf("Background.io.execute"))
        assertTrue(render.indexOf("SavedSessions.load(") < render.indexOf("Background.onMain"))
        assertEquals(2, Regex("isDestroyed \\|\\| isFinishing \\|\\| generation != sessionsGeneration").findAll(render).count())
        val show = method(rf(), "showSessions")
        assertFalse(show.contains("store.")); assertFalse(show.contains("readText"))
        assertTrue(method(rf(), "render").contains("++sessionsGeneration"))
        assertTrue(rf().substringAfter("override fun onDestroy()").contains("++sessionsGeneration"))
    }

    /** Problems with a delete action; empty when it is safe against a session started during the delete. */
    private fun deleteProblems(action: String, call: String): List<String> {
        val worker = action.indexOf("Background.io.execute")
        val onMain = action.indexOf("Background.onMain")
        val before = action.substring(0, worker.coerceAtLeast(0))
        return listOfNotNull(
            "running session read on the worker".takeIf { action.substring(worker, onMain).contains("rf.activeId") },
            "running session not read before the worker".takeIf { !before.contains("val activeId = rf.activeId") },
            "new starts not held".takeIf { !before.contains("RfRuntime.deletesInFlight.incrementAndGet()") },
            "hold not released on every path".takeIf { !action.substring(worker, onMain).contains("finally { RfRuntime.deletesInFlight.decrementAndGet() }") },
            "cached list not cleared at the tap".takeIf { !before.contains("lastSessions = null") },
            "delete not on the worker".takeIf { action.indexOf(call) !in worker..onMain },
            "screen not refreshed".takeIf { !action.substringAfter("Background.onMain").contains("render()") },
            "closed screen touched".takeIf { !action.substringAfter("Background.onMain").contains("if (isDestroyed || isFinishing) return@onMain") },
        )
    }

    private fun deleteAll() = method(rf(), "confirmDeleteAll").substringAfter("setPositiveButton")

    @Test fun rfDeletesHoldNewSessionsAndUseTheSessionRunningAtTheTap() {
        assertEquals(emptyList<String>(), deleteProblems(method(rf(), "deleteSession"), "rf.store.delete(id, activeId)"))
        assertEquals(emptyList<String>(), deleteProblems(deleteAll(), "rf.store.deleteAll(activeId)"))
        // A partial Delete all failure still refreshes, so the list shows what is left.
        assertFalse(deleteAll().substringAfter("Background.onMain").contains("if (deleted) {"))
    }

    @Test fun deleteGuardDetectsTheOldRace() {
        // The Nov 26 Neon P1: the running session read on the worker, with nothing holding new starts.
        val old = deleteAll().replace("rf.store.deleteAll(activeId)", "rf.store.deleteAll(rf.activeId)")
            .replace("RfRuntime.deletesInFlight.incrementAndGet()", "")
        assertTrue(deleteProblems(old, "rf.store.deleteAll(rf.activeId)").containsAll(
            listOf("running session read on the worker", "new starts not held")))
    }

    @Test fun everySessionStartWaitsForARunningDelete() {
        assertTrue(method(rf(), "deleteRunning").contains("RfRuntime.deletesInFlight.get() > 0"))
        assertTrue(method(rf(), "beginLive").lines()[1].contains("if (deleteRunning()) return"))
        assertTrue(method(rf(), "startSimulated").lines()[1].contains("if (deleteRunning()) return"))
        assertTrue(method(rf(), "startReplay").contains("else if (!deleteRunning()) started(rf.start(req))"))
        // Only these three places start a session.
        assertEquals(3, Regex("rf\\.start\\(").findAll(rf()).count())
    }

    @Test fun cachedSessionListIsDroppedWheneverSessionsMayHaveChanged() {
        assertTrue(method(rf(), "renderSessions").contains("lastSessions = snapshot?.let { it to showAll }"))
        assertTrue(method(rf(), "started").contains("lastSessions = null"))
        assertTrue(rf().substringAfter("IMPORT_ZIP").contains("lastSessions = null"))
        // Both places that notice a session ended.
        assertEquals(2, Regex("resultId = rf.lastEndedId; result = null; lastSessions = null").findAll(rf()).count())
        // A delete clears it at the tap and again when it finishes, since a list read queued
        // before the tap can refill it while the delete runs.
        for (name in listOf("deleteSession", "confirmDeleteAll")) {
            val onMain = method(rf(), name).substringAfter("Background.onMain")
            assertTrue(name, onMain.substringBefore("render()").contains("lastSessions = null"))
        }
    }

    @Test fun homeScrollRestoreChecksTheScreenAndGeneration() {
        assertTrue(method(home(), "restoreScroll").contains("!isDestroyed && !isFinishing && generation == renderGeneration"))
        assertEquals(0, Regex("scroll\\.post").findAll(method(home(), "show")).count())
        assertEquals(2, Regex("restoreScroll\\(oldScroll, generation\\)").findAll(method(home(), "show")).count())
    }
}
