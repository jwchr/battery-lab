package com.batterylab.personal.rf

import com.batterylab.rf.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuideHistoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun session(store: SessionStore, start: Long, scope: Scope = Scope.MY_DEVICES,
                        durationS: Int = 90, plannedS: Int = 90, provenance: Provenance = Provenance.LIVE,
                        imported: Boolean = false, state: String = Keys.STATE_COMPLETE,
                        markers: List<String> = listOf("near", "moving", "far")) {
        val writer = SessionWriter(store.dir(store.newId(start)), linkedMapOf(
            Keys.FORMAT to "1", Keys.MIN_READER to "1", Keys.START_BOOT_NS to "1000000000",
            Keys.START_WALL_MS to start.toString(), Keys.SCOPE to scope.id,
            Keys.PROVENANCE to provenance.name, Keys.IMPORTED to imported.toString(),
            Keys.DURATION_LIMIT_S to plannedS.toString(), Keys.STATE to Keys.STATE_RECORDING,
        ), SessionController.SIZE_LIMIT_BYTES)
        markers.forEachIndexed { i, marker -> writer.event(1_000_000_000L + i, start, EventType.MARKER, text = marker) }
        writer.finish(mapOf(Keys.END_BOOT_NS to (1_000_000_000L + durationS * 1_000_000_000L).toString(), Keys.STATE to state))
    }

    @Test fun historyExcludesOldReplaySimulationImportAndInterruptedSessions() {
        val store = SessionStore(temp.newFolder())
        session(store, 99)
        session(store, 101)
        session(store, 102, durationS = 60)
        session(store, 103, durationS = 180, plannedS = 180)
        session(store, 104, markers = listOf("near", "far"))
        session(store, 105, state = Keys.STATE_INTERRUPTED)
        session(store, 106, state = Keys.STATE_RECORDING)
        session(store, 107, provenance = Provenance.SIMULATED)
        session(store, 108, provenance = Provenance.REPLAY)
        session(store, 109, imported = true)
        session(store, 110, Scope.EVERYTHING_NEARBY, 300, 300)
        session(store, 111, Scope.EVERYTHING_NEARBY, 299, 300)
        val history = GuideHistory.read(store, 100, 100, 111)
        assertEquals(7, history.todayCount)
        assertEquals(6, history.sessions.size)
        val guide = GuideSteps.onEvent(GuideSteps.start(100).copy(current = 4), history)
        assertEquals(1, guide.walks)
        assertTrue(guide.nearbyFinished)
        assertEquals(history, GuideHistory.read(store, 100, 100, 111))
        assertFalse(GuideHistory.read(store, 112, 112, 200).sessions.isNotEmpty())
    }

    @Test fun reopeningRecoversWalkProgressWithoutDuplicatesAndDeletionRecounts() {
        val store = SessionStore(temp.newFolder())
        session(store, 101); session(store, 102)
        val initial = GuideSteps.start(100).copy(current = 4)
        val first = GuideSteps.onEvent(initial, GuideHistory.read(store, 100, 100, 200))
        assertEquals(2, first.walks)
        val restored = GuideSteps.load(GuideSteps.save(first))
        val again = GuideSteps.onEvent(restored, GuideHistory.read(store, 100, 100, 200))
        assertEquals(2, again.walks)
        session(store, 103)
        val finished = GuideSteps.onEvent(again, GuideHistory.read(store, 100, 100, 200))
        assertEquals(5, finished.current)
        assertEquals(3, finished.walks)
        assertEquals(0, GuideSteps.onEvent(initial, GuideHistory.read(store, 104, 100, 200)).walks)
        store.ids().forEach { store.delete(it, null) }
        assertEquals(0, GuideSteps.onEvent(first, GuideHistory.read(store, 100, 100, 200)).walks)
    }
}
