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
                        markers: List<String> = listOf("near", "moving", "far"),
                        markerSeconds: List<Long>? = null, stopReason: String = StopReason.STOP) {
        val writer = SessionWriter(store.dir(store.newId(start)), linkedMapOf(
            Keys.FORMAT to "1", Keys.MIN_READER to "1", Keys.START_BOOT_NS to "1000000000",
            Keys.START_WALL_MS to start.toString(), Keys.SCOPE to scope.id,
            Keys.PROVENANCE to provenance.name, Keys.IMPORTED to imported.toString(),
            Keys.DURATION_LIMIT_S to plannedS.toString(), Keys.STATE to Keys.STATE_RECORDING,
        ), SessionController.SIZE_LIMIT_BYTES)
        markers.forEachIndexed { i, marker -> writer.event(1_000_000_000L + (markerSeconds?.get(i)?.times(1_000_000_000L) ?: i.toLong()), start, EventType.MARKER, text = marker) }
        writer.finish(mapOf(Keys.END_BOOT_NS to (1_000_000_000L + durationS * 1_000_000_000L).toString(), Keys.STATE to state, Keys.STOP_REASON to stopReason))
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
        assertEquals(4, finished.current)
        assertEquals(7, finished.subStep)
        assertEquals(3, finished.walks)
        assertEquals(0, GuideSteps.onEvent(initial, GuideHistory.read(store, 104, 100, 200)).walks)
        store.ids().forEach { store.delete(it, null) }
        assertEquals(0, GuideSteps.onEvent(first, GuideHistory.read(store, 100, 100, 200)).walks)
    }

    @Test fun historyCountsWalksWithoutNearAndKeepsOnlyStandardMarkers() {
        val store = SessionStore(temp.newFolder())
        session(store, 101, markers = listOf("moving", "far", "other"))
        session(store, 102, markers = listOf("near", "far"))
        session(store, 103, markers = listOf("near", "moving"))
        session(store, 104, durationS = 89, markers = listOf("moving", "far"))
        val history = GuideHistory.read(store, 100, 100, 200)
        assertEquals(setOf("moving", "far"), history.sessions.single { it.timeMs == 101L }.markers)
        assertEquals(setOf("moving", "far"), history.sessions.single { it.timeMs == 104L }.markers)
        val guide = GuideSteps.onEvent(GuideSteps.start(100).copy(current = 4), history)
        assertEquals(1, guide.walks)
        assertEquals(1, guide.walkNumber)
        assertEquals(7, guide.subStep)
    }
    @Test fun historyUsesActualMarkerTimesAndEndTimeForShorterWalks() {
        val store = SessionStore(temp.newFolder())
        session(store, 101, durationS = 70, plannedS = 180, markerSeconds = listOf(0, 30, 40))
        session(store, 102, durationS = 70, plannedS = 180, markerSeconds = listOf(0, 24, 40))
        session(store, 103, durationS = 70, plannedS = 180, markerSeconds = listOf(0, 30, 46))
        session(store, 104, durationS = 70, plannedS = 180, markers = listOf("far", "near", "moving"),
            markerSeconds = listOf(0, 10, 40))
        session(store, 105, durationS = 70, plannedS = 180, state = Keys.STATE_INTERRUPTED,
            markerSeconds = listOf(0, 30, 40))
        val history = GuideHistory.read(store, 100, 100, 200)
        assertEquals(4, history.sessions.size)
        assertEquals(listOf(101L), history.sessions.filter(GuideSteps::qualifiesWalk).map { it.timeMs })
        assertEquals(30_000_000_000L, history.sessions.first().markerTimesNs["moving"])
        assertEquals(70_000_000_000L, history.sessions.first().durationNs)
        val state = GuideSteps.onEvent(GuideSteps.start(100).copy(current = 4), history)
        assertEquals(1, state.walks)
        assertEquals(1, state.walkNumber)
        assertEquals(7, state.subStep)
    }

    @Test fun leavingTheScreenOrHittingALimitDoesNotCountAnEarlyWalk() {
        val store = SessionStore(temp.newFolder())
        session(store, 101, durationS = 70, plannedS = 180, markerSeconds = listOf(0, 30, 40), stopReason = StopReason.LEFT_SCREEN)
        session(store, 102, durationS = 70, plannedS = 180, markerSeconds = listOf(0, 30, 40), stopReason = StopReason.SIZE_LIMIT)
        session(store, 103, durationS = 90, markers = listOf("moving", "far"), stopReason = StopReason.TIME_LIMIT)
        session(store, 104, durationS = 90, markers = listOf("moving", "far"), stopReason = StopReason.LEFT_SCREEN)
        val history = GuideHistory.read(store, 100, 100, 200)
        assertEquals(listOf(103L, 104L), history.sessions.filter(GuideSteps::qualifiesWalk).map { it.timeMs })
    }

    @Test fun duplicateMarkersCannotHideAnEarlierOutOfOrderMarker() {
        val store = SessionStore(temp.newFolder())
        session(store, 101, durationS = 90, plannedS = 180,
            markers = listOf("moving", "near", "moving", "far"), markerSeconds = listOf(0, 10, 40, 50))
        val history = GuideHistory.read(store, 100, 100, 200)
        assertFalse(GuideSteps.qualifiesWalk(history.sessions.single()))
    }
}
