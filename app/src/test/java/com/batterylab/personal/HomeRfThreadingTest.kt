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

    @Test fun rfDeletesRunOnWorkerUsingActiveIdAtExecutionTime() {
        listOf("deleteSession" to "rf.store.delete(id, rf.activeId)", "confirmDeleteAll" to "rf.store.deleteAll(rf.activeId)").forEach { (name, call) ->
            val action = method(rf(), name)
            assertTrue(action.indexOf(call) > action.indexOf("Background.io.execute"))
            assertTrue(action.indexOf(call) < action.indexOf("Background.onMain"))
            assertTrue(action.substringAfter("Background.onMain").contains("render()"))
            assertTrue(action.substringAfter("Background.onMain").contains("if (isDestroyed || isFinishing) return@onMain"))
        }
    }
}
