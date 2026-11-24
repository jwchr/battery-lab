package com.batterylab.personal.rf

import com.batterylab.personal.rf.GuideSteps.Action
import com.batterylab.personal.rf.GuideSteps.State
import com.batterylab.personal.rf.GuideSteps.Event
import com.batterylab.personal.rf.GuideSteps.Tap
import com.batterylab.personal.rf.GuideSteps.Tick
import com.batterylab.personal.rf.GuideSteps.SessionStarted
import com.batterylab.personal.rf.GuideSteps.SessionFinished
import com.batterylab.personal.rf.GuideSteps.SessionContinued
import com.batterylab.personal.rf.GuideSteps.SessionEnded
import com.batterylab.personal.rf.GuideSteps.BluetoothChanged
import com.batterylab.personal.rf.GuideSteps.LocationChanged
import com.batterylab.personal.rf.GuideSteps.RecreatedDuringSession
import com.batterylab.personal.rf.GuideSteps.RingLinkNotConnected
import com.batterylab.personal.rf.GuideSteps.HistoryLoaded
import com.batterylab.personal.rf.GuideSteps.Back
import com.batterylab.rf.Scope
import org.junit.Assert.*
import org.junit.Test

class GuideStepsTest {
    private fun at(step: Int, sub: Int = 0) = GuideSteps.start(100).copy(current = step, subStep = sub)
    private fun send(s: State, vararg events: Event) = events.fold(s, GuideSteps::onEvent)
    private fun tap(s: State, now: Long = 200) = GuideSteps.onEvent(s, Tap(GuideSteps.page(s).primary!!.action, now))
    private fun start(s: State, now: Long = 200): State {
        val action = GuideSteps.page(s).primary!!.action as Action.Start
        assertTrue(action.seconds in setOf(90, 180, 300, 600))
        assertEquals(s, tap(s, now))
        return GuideSteps.onEvent(s, SessionStarted(action.scope, now))
    }
    private fun walk(time: Long = 200, planned: Int = 180, duration: Long = 70,
                     near: Long = 0, moving: Long = 30, far: Long = 40) = SessionFinished(
        Scope.MY_DEVICES, duration, setOf("near", "moving", "far"), time, planned,
        markerTimesNs = mapOf("near" to near * 1_000_000_000, "moving" to moving * 1_000_000_000, "far" to far * 1_000_000_000))
    private fun history(vararg sessions: SessionFinished) = HistoryLoaded(sessions.toList(), sessions.size)

    @Test fun permissionsStartThenAnswer() {
        val first = at(0)
        assertEquals(first, send(first, SessionStarted(Scope.MY_LINKS, 99), Tap(Action.Yes, 200)))
        val question = start(first)
        assertEquals(1, question.subStep)
        val next = tap(question)
        assertEquals(1, next.current)
        assertEquals(true, next.answers["answer_precise_prompt"])
    }

    @Test fun bluetoothSettingsStartOffOnTwentySecondsAndResult() {
        var s = tap(at(1))
        assertEquals("I'm back", GuideSteps.page(s).primary!!.label)
        s = tap(s)
        assertEquals(Action.Start(Scope.MY_DEVICES, 180), GuideSteps.page(s).primary!!.action)
        s = start(s)
        assertEquals(3, s.subStep)
        s = send(s, BluetoothChanged(true, 300))
        assertEquals(3, s.subStep)
        s = send(s, BluetoothChanged(false, 500), BluetoothChanged(true, 1000))
        assertEquals(5, s.subStep)
        assertEquals(21000, s.countdownEndMs)
        s = send(s, SessionContinued, Tick(20999))
        assertEquals(5, s.subStep)
        s = send(s, Tick(21000))
        assertEquals(true, s.answers["answer_session_kept_going"])
        assertTrue(GuideSteps.needsStop(s))
        assertEquals(2, tap(s).current)
    }

    @Test fun bluetoothResultIsNoIfSessionEnded() {
        val s = send(start(at(1, 2)), BluetoothChanged(false, 500), BluetoothChanged(true, 1000),
            SessionContinued, SessionEnded, Tick(21000))
        assertEquals(false, s.answers["answer_session_kept_going"])
        assertEquals(6, s.subStep)
    }

    @Test fun replacementSessionDuringBluetoothCountdownCannotReuseEarlierEvidence() {
        val waiting = send(start(at(1, 2)), BluetoothChanged(false, 500), BluetoothChanged(true, 1000), SessionContinued)
        assertTrue(waiting.bluetoothOff && waiting.bluetoothBack && waiting.sessionContinued)
        val ended = send(waiting, SessionEnded)
        assertFalse(ended.bluetoothOff)
        assertFalse(ended.bluetoothBack)
        assertFalse(ended.sessionContinued)
        val result = send(ended, SessionStarted(Scope.MY_DEVICES, 2000), SessionContinued, Tick(21000))
        assertEquals(6, result.subStep)
        assertEquals(false, result.answers["answer_session_kept_going"])
    }

    @Test fun bluetoothOffEvidenceDoesNotJoinDifferentSessions() {
        var s = send(start(at(1, 2)), BluetoothChanged(false, 500), SessionEnded)
        s = GuideSteps.load(GuideSteps.save(s))
        s = send(s, SessionStarted(Scope.MY_DEVICES, 1000), BluetoothChanged(true, 2000), SessionContinued, Tick(22000))
        assertEquals(3, s.subStep)
        assertNull(s.answers["answer_session_kept_going"])
        assertFalse(s.bluetoothOff)
        assertFalse(s.bluetoothBack)
        assertFalse(s.sessionContinued)
        assertFalse(s.retryBluetooth)
        s = send(s, BluetoothChanged(false, 23000), BluetoothChanged(true, 24000), SessionContinued, Tick(44000))
        assertEquals(true, s.answers["answer_session_kept_going"])
    }

    @Test fun bluetoothEarlyStopAndProcessDeathReturnBothWaitingPagesToStart() {
        for (sub in 3..4) for (reload in listOf(false, true)) {
            val running = start(at(1, 2)).copy(subStep = sub, bluetoothOff = sub == 4)
            val restored = if (reload) GuideSteps.load(GuideSteps.save(running)) else running
            val retry = send(restored, SessionEnded)
            assertEquals(2, retry.subStep)
            assertNull(retry.runningScope)
            assertEquals(0, retry.countdownEndMs)
            assertEquals(0, retry.attemptStartedAtMs)
            assertFalse(retry.bluetoothOff)
            assertFalse(retry.bluetoothBack)
            assertFalse(retry.sessionContinued)
            assertEquals("That session stopped early. Press Start to try again.", GuideSteps.page(retry).instruction)
            assertEquals(Action.Start(Scope.MY_DEVICES, 180), GuideSteps.page(retry).primary!!.action)
            val saved = GuideSteps.load(GuideSteps.save(retry))
            assertEquals(GuideSteps.page(retry), GuideSteps.page(saved))
            assertEquals(retry, send(retry, BluetoothChanged(false, 500), BluetoothChanged(true, 1000), Tick(30000)))
            assertFalse(start(saved, 40000).retryBluetooth)
        }
    }

    @Test fun bluetoothChangesWithoutMyDevicesCannotAdvanceOrCreateEvidence() {
        for (scope in listOf(null, Scope.MY_LINKS, Scope.EVERYTHING_NEARBY)) for (sub in 3..4) {
            val s = at(1, sub).copy(runningScope = scope, bluetoothOff = sub == 4)
            assertEquals(s, send(s, BluetoothChanged(false, 500), BluetoothChanged(true, 1000), SessionContinued))
        }
    }

    @Test fun backFromBluetoothResultStartsOverAndPreservesEitherAnswer() {
        for (yes in listOf(false, true)) {
            val s = at(1, 6).copy(answers = mapOf("answer_session_kept_going" to yes),
                bluetoothOff = true, bluetoothBack = true, sessionContinued = true)
            val back = send(s, Back(1000), SessionEnded, Tick(21000))
            assertEquals(2, back.subStep)
            assertEquals(0, back.countdownEndMs)
            assertEquals(yes, back.answers["answer_session_kept_going"])
            assertEquals(Action.Start(Scope.MY_DEVICES, 180), GuideSteps.page(back).primary!!.action)
            assertFalse(back.bluetoothOff)
            assertFalse(back.bluetoothBack)
            assertFalse(back.sessionContinued)
            assertEquals(3, start(back, 22000).subStep)
        }
    }

    @Test fun backFromAnyLiveBluetoothSubStepStartsOverWithCleanFlags() {
        for (sub in 3..5) {
            val s = at(1, sub).copy(runningScope = Scope.MY_DEVICES, countdownEndMs = 21000,
                bluetoothOff = true, bluetoothBack = true, sessionContinued = true)
            val back = send(s, Back(1000))
            assertEquals(2, back.subStep)
            assertEquals(0, back.countdownEndMs)
            assertEquals(Action.Start(Scope.MY_DEVICES, 180), GuideSteps.page(back).primary!!.action)
            assertFalse(back.bluetoothOff)
            assertFalse(back.bluetoothBack)
            assertFalse(back.sessionContinued)
        }
    }

    @Test fun rotationOnlyAdvancesWhileInTheRotationSubStep() {
        assertEquals(0, send(at(2), RecreatedDuringSession).subStep)
        assertEquals(3, send(start(at(2)), RecreatedDuringSession).current)
        assertEquals(3, tap(start(at(2))).current)
        assertEquals(2, send(start(at(2)), SessionEnded, RecreatedDuringSession).current)
    }

    @Test fun locationSwitchesThenDashboardThenQuestion() {
        var s = at(3)
        assertEquals(s, tap(s))
        s = send(s, LocationChanged(false))
        assertEquals(1, s.subStep)
        s = start(s)
        assertEquals(2, s.subStep)
        assertEquals(s, tap(s))
        s = send(s, LocationChanged(true))
        assertEquals(3, s.subStep)
        s = tap(s)
        assertEquals(4, s.subStep)
        s = tap(s)
        assertEquals(5, s.subStep)
        s = send(s, Tap(Action.No, 300))
        assertEquals(4, s.current)
        assertEquals(false, s.answers["answer_privacy_listed"])
    }

    @Test fun walksHaveOneActionAtATimeAndStopAfterTheFarCountdown() {
        var s = tap(at(4))
        val sessions = mutableListOf<SessionFinished>()
        for (n in 1..3) {
            assertEquals(n, s.walkNumber)
            assertEquals(1, s.subStep)
            val time = n * 100000L
            s = start(s, time)
            assertEquals(time + 30000, s.countdownEndMs)
            assertNull(GuideSteps.page(s).primary)
            s = send(s, Tick(time + 29999))
            assertEquals(2, s.subStep)
            s = send(s, Tick(time + 30000))
            assertEquals("Start walking", GuideSteps.page(s).primary!!.label)
            s = tap(s, time + 30000)
            assertEquals("I'm there", GuideSteps.page(s).primary!!.label)
            s = tap(s, time + 40000)
            assertEquals(time + 70000, s.countdownEndMs)
            s = send(s, Tick(time + 70000))
            assertTrue(GuideSteps.needsStop(s))
            sessions += walk(time)
            s = send(s, SessionEnded, HistoryLoaded(sessions.toList(), n))
            assertEquals(7, s.subStep)
            s = tap(s)
        }
        assertEquals(5, s.current)
        assertEquals(3, s.optionalBaseline)
    }

    @Test fun optionalWalksHaveConsentPreparationAndTheSameWalkFlow() {
        var s = at(5).copy(walks = 3, optionalBaseline = 3)
        assertEquals(Action.Skip, GuideSteps.page(s).secondary!!.action)
        s = tap(s)
        assertEquals("Done", GuideSteps.page(s).primary!!.label)
        s = tap(s)
        s = start(s)
        assertEquals(3, s.subStep)
        s = send(s, Tick(30200))
        s = tap(s)
        s = tap(s, 40200)
        s = send(s, Tick(70200), SessionEnded, history(walk(101), walk(102), walk(103), walk(200)))
        assertEquals(8, s.subStep)
        assertEquals(2, tap(s).walkNumber)
        val skipped = send(at(5), Tap(Action.Skip, 200))
        assertEquals(6, skipped.current)
        assertTrue(skipped.isSkipped(5))
    }

    @Test fun earlyStopRetriesTheSameWalkInEveryRunningPhase() {
        for (step in 4..5) for (phase in 1..4) {
            val startSub = if (step == 4) 1 else 2
            val s = at(step, startSub + phase).copy(walkNumber = 2, runningScope = Scope.MY_DEVICES,
                countdownEndMs = 30000, attemptStartedAtMs = 200)
            val retry = send(s, SessionEnded)
            assertEquals(startSub, retry.subStep)
            assertEquals(2, retry.walkNumber)
            assertEquals(0, retry.countdownEndMs)
            assertTrue(GuideSteps.page(retry).instruction.contains("stopped early"))
            assertEquals(startSub + 1, start(retry, 1000).subStep)
        }
    }

    @Test fun processDeathWithAnUnfinishedWalkRetriesAfterReload() {
        val running = start(tap(at(4)))
        val restored = GuideSteps.load(GuideSteps.save(running))
        assertNull(restored.runningScope)
        val retry = send(restored, SessionEnded, history())
        assertTrue(retry.retryWalk)
        assertEquals(1, retry.subStep)
        assertEquals(1, retry.walkNumber)
    }

    @Test fun interruptedSaveRetriesAndCompletedSaveRecoversWithoutCountingTwice() {
        val saving = at(4, 6).copy(attemptStartedAtMs = 200)
        assertTrue(send(saving, history()).retryWalk)
        val restored = send(GuideSteps.load(GuideSteps.save(saving)), history(walk()))
        assertEquals(7, restored.subStep)
        assertEquals(1, restored.walks)
        assertEquals(restored, send(restored, history(walk()), history(walk())))
    }

    @Test fun threeSavedWalksDoNotChangeAnyLiveWalkPhase() {
        val saved = history(walk(101), walk(102), walk(103))
        for (step in 4..5) for (phase in 1..4) {
            val startSub = if (step == 4) 1 else 2
            val s = start(at(step, startSub).copy(walkNumber = 3, walks = 3, todayCount = 3), 200)
                .copy(subStep = startSub + phase)
            assertEquals(s, send(s, saved))
        }
    }

    @Test fun savingCompletesOnlyTheCurrentAttemptAndKeepsTheWalkNumber() {
        for (step in 4..5) {
            val startSub = if (step == 4) 1 else 2
            val baseline = if (step == 5) 3 else 0
            val s = at(step, startSub + 5).copy(walkNumber = 2, attemptStartedAtMs = 200,
                optionalBaseline = baseline, countdownEndMs = 70000)
            val earlier = (1..baseline + 2).map { walk(100L + it) }
            val done = send(s, HistoryLoaded(earlier + walk(200), baseline + 3))
            assertEquals(startSub + 6, done.subStep)
            assertEquals(2, done.walkNumber)
            assertEquals(0, done.countdownEndMs)
            assertFalse(done.retryWalk)
            assertEquals("Walk 2 done.", GuideSteps.page(done).instruction)
            assertEquals(done, send(done, HistoryLoaded(earlier + walk(200), baseline + 3)))
        }
    }

    @Test fun savingRetriesANonQualifyingAttemptEvenWithThreeOlderWalks() {
        for (step in 4..5) for (running in listOf(null, Scope.MY_DEVICES)) {
            val startSub = if (step == 4) 1 else 2
            val s = at(step, startSub + 5).copy(walkNumber = 2, attemptStartedAtMs = 200,
                runningScope = running, countdownEndMs = 70000)
            val retry = send(s, history(walk(101), walk(102), walk(103), walk(200, moving = 24)))
            assertEquals(startSub, retry.subStep)
            assertEquals(2, retry.walkNumber)
            assertEquals(0, retry.countdownEndMs)
            assertEquals(0, retry.attemptStartedAtMs)
            assertNull(retry.runningScope)
            assertTrue(retry.retryWalk)
        }
    }

    @Test fun savingWaitsForTheAttemptWhileRunningAndRetriesIfItEndedWithoutSaving() {
        for (step in 4..5) {
            val startSub = if (step == 4) 1 else 2
            val s = at(step, startSub + 5).copy(walkNumber = 3, attemptStartedAtMs = 200,
                runningScope = Scope.MY_DEVICES, walks = 3, todayCount = 3)
            val saved = history(walk(101), walk(102), walk(103))
            assertEquals(s, send(s, saved))
            val retry = send(s, SessionEnded, saved)
            assertEquals(startSub, retry.subStep)
            assertEquals(3, retry.walkNumber)
            assertTrue(retry.retryWalk)
        }
    }

    @Test fun walkSavingIgnoresReadsStartedWhileRunningAndAcceptsStoppedReads() {
        for (step in 4..5) for (ended in listOf(false, true)) {
            val startSub = if (step == 4) 1 else 2
            val running = at(step, startSub + 5).copy(attemptStartedAtMs = 200,
                walkNumber = 2, runningScope = Scope.MY_DEVICES, walks = 1, todayCount = 1)
            val s = if (ended) send(running, SessionEnded) else running
            for (sessions in listOf(emptyList(), listOf(walk(101)), listOf(walk(101), walk()))) {
                val stale = HistoryLoaded(sessions, sessions.size, readStartedAfterStop = false)
                assertFalse(GuideSteps.acceptsHistory(s, stale))
                assertEquals(s, send(s, stale))
            }
            val stopped = send(s, SessionEnded)
            val good = send(stopped, history(walk(101), walk()))
            assertEquals(startSub + 6, good.subStep)
            assertEquals(2, good.walks)
            assertEquals(2, good.walkNumber)
            val retry = send(stopped, history(walk(101)))
            assertEquals(startSub, retry.subStep)
            assertTrue(retry.retryWalk)
        }
    }

    @Test fun nearbyIgnoresReadsStartedWhileRunningAndAcceptsStoppedReads() {
        val running = start(at(7))
        val good = SessionFinished(Scope.EVERYTHING_NEARBY, 300, emptySet(), 200, 300)
        for (ended in listOf(false, true)) {
            val s = if (ended) send(running, SessionEnded) else running
            for (sessions in listOf(emptyList(), listOf(good))) {
                val stale = HistoryLoaded(sessions, sessions.size, readStartedAfterStop = false)
                assertFalse(GuideSteps.acceptsHistory(s, stale))
                assertEquals(s, send(s, stale))
            }
            val stopped = send(s, SessionEnded)
            assertEquals(8, send(stopped, history(good)).current)
            assertEquals(0, send(stopped, history()).subStep)
        }
    }

    @Test fun historyStartedWhileRunningStillUpdatesOtherGuidePages() {
        val updated = send(at(3, 1), history(walk()).copy(readStartedAfterStop = false))
        assertEquals(1, updated.walks)
        assertEquals(1, updated.todayCount)
        assertEquals(1, updated.subStep)
    }

    @Test fun finishedSessionsAreRecordsAndOnlyFullHistoryUpdatesCounts() {
        assertFalse(Event::class.java.isAssignableFrom(SessionFinished::class.java))
        val nearby = SessionFinished(Scope.EVERYTHING_NEARBY, 300, emptySet(), 300, 300)
        val first = send(at(8), history(walk(101), walk(102), nearby))
        val all = history(walk(101), walk(102), nearby, walk(400))
        val updated = send(first, all)
        assertEquals(3, updated.walks)
        assertTrue(updated.nearbyFinished)
        assertEquals(4, updated.todayCount)
        assertEquals(updated, send(updated, all, all))
        val restored = send(GuideSteps.load(GuideSteps.save(updated)), all)
        assertEquals(updated.walks, restored.walks)
        assertEquals(updated.nearbyFinished, restored.nearbyFinished)
    }

    @Test fun backFromEveryWalkPhaseReturnsToStartWithoutAnEarlyStopMessage() {
        for (step in 4..5) for (phase in 1..6) for (number in 1..3) {
            val startSub = if (step == 4) 1 else 2
            val s = at(step, startSub + phase).copy(walkNumber = number, retryWalk = true,
                runningScope = Scope.MY_DEVICES, countdownEndMs = 70000, attemptStartedAtMs = 200)
            val back = send(s, Back(1000))
            assertEquals(step, back.current)
            assertEquals(startSub, back.subStep)
            assertEquals(number, back.walkNumber)
            assertEquals(0, back.countdownEndMs)
            assertFalse(back.retryWalk)
            assertTrue(back.reviewing)
            assertFalse(GuideSteps.page(back).instruction.contains("stopped early"))
            val refreshed = send(back, SessionEnded, history(walk(101), walk(102), walk(103)))
            assertEquals(startSub, refreshed.subStep)
            assertEquals(number, refreshed.walkNumber)
            assertFalse(refreshed.retryWalk)
        }
    }

    @Test fun backReviewSurvivesReloadAndHistoryUntilStartingOrAdvancing() {
        val saved = history(walk(101), walk(102), walk(103))
        for (step in 4..5) {
            val startSub = if (step == 4) 1 else 2
            val back = send(at(step, startSub + 6).copy(walkNumber = 3), Back(1000))
            val loaded = GuideSteps.load(GuideSteps.save(back))
            assertTrue(loaded.reviewing)
            val refreshed = send(loaded, saved)
            assertEquals(startSub, refreshed.subStep)
            assertEquals(3, refreshed.walkNumber)
            val started = start(refreshed, 2000)
            assertFalse(started.reviewing)
            assertEquals(startSub + 1, send(started, saved).subStep)
            assertFalse(tap(at(step, startSub + 6).copy(walkNumber = 3, reviewing = true)).reviewing)
        }
        // Entering a walk step from the following step also counts as reviewing.
        for (step in 5..6) {
            val back = send(at(step), Back(1000), saved)
            assertEquals(step - 1, back.current)
            assertEquals(0, back.subStep)
            assertTrue(back.reviewing)
        }
    }

    @Test fun oldWalksStillCountOnArrivalAndOptionalWalksHaveTheirOwnBaseline() {
        var s = send(at(4), history(walk(99), walk(101), walk(102), walk(103)))
        assertEquals(3, s.walks)
        assertEquals(7, s.subStep)
        s = tap(s)
        assertEquals(5, s.current)
        assertEquals(3, s.optionalBaseline)
        s = send(s, history(walk(101), walk(102), walk(103)))
        assertEquals(0, s.subStep)
        s = send(s, history(walk(101), walk(102), walk(103), walk(104), walk(105), walk(106)))
        assertEquals(8, s.subStep)
        assertEquals(6, tap(s).current)
    }

    @Test fun ringAwayPreparationStartAndAnswerOrDetection() {
        val started = start(tap(at(6)))
        assertEquals(2, started.subStep)
        assertEquals(7, send(started, RingLinkNotConnected).current)
        assertEquals(true, send(started, RingLinkNotConnected).answers["answer_ring_not_connected"])
        val no = send(started, Tap(Action.No, 300))
        assertEquals(false, no.answers["answer_ring_not_connected"])
        assertEquals(6, send(at(6), RingLinkNotConnected).current)
    }

    @Test fun nearbyNeedsTheFullSavedFiveMinuteSession() {
        val started = start(at(7))
        assertEquals(300200, started.countdownEndMs)
        assertEquals(7, send(started, Tick(300200)).current)
        val good = SessionFinished(Scope.EVERYTHING_NEARBY, 300, emptySet(), 200, 300)
        assertEquals(8, send(started, SessionEnded, history(good)).current)
        val retry = send(started, SessionEnded, history(good.copy(durationS = 299)))
        assertEquals(0, retry.subStep)
        assertEquals(7, retry.current)
        assertEquals(7, send(started, SessionEnded, history(good.copy(complete = false))).current)
        assertEquals(7, send(started, SessionEnded, history(good.copy(timeMs = 150))).current)
    }

    @Test fun batteryMainScreenThenAnswer() {
        val question = tap(at(8))
        assertEquals(1, question.subStep)
        val yes = tap(question)
        assertEquals(9, yes.current)
        assertEquals(true, yes.answers["answer_ring_battery"])
    }

    @Test fun tonightLaterKeepsTheStepAndFinishNeedsATap() {
        val later = send(at(9), Tap(Action.Later, 200))
        assertEquals(at(9), later)
        val tonight = tap(later)
        assertEquals(10, tonight.current)
        assertEquals(true, tonight.answers["answer_wearable_connected"])
        assertEquals("Finish", GuideSteps.page(tonight).primary!!.label)
        assertTrue(tap(tonight).finished)
    }

    @Test fun everyAnswerSurvivesReloadIncludingNo() {
        for ((step, sub, key) in listOf(Triple(0, 1, "answer_precise_prompt"), Triple(3, 5, "answer_privacy_listed"),
            Triple(6, 2, "answer_ring_not_connected"), Triple(8, 1, "answer_ring_battery"), Triple(9, 0, "answer_wearable_connected"))) {
            for (yes in listOf(true, false)) {
                val answered = send(at(step, sub), Tap(if (yes) Action.Yes else Action.No, 200))
                val restored = GuideSteps.load(GuideSteps.save(answered))
                assertEquals(yes, restored.answers[key])
                assertEquals(1, GuideSteps.answerSummary(restored).size)
            }
        }
    }

    @Test fun allCountdownEndTimesSurviveReloadWithoutResetting() {
        val timers = listOf(at(1, 5), at(4, 2), at(4, 5), at(5, 3), at(5, 6), at(7, 1))
        for (timer in timers) {
            val s = timer.copy(countdownEndMs = 50000)
            val loaded = GuideSteps.load(GuideSteps.save(s))
            assertEquals(50000, loaded.countdownEndMs)
            assertEquals(s.subStep, loaded.subStep)
            assertEquals(20, GuideSteps.secondsLeft(loaded, 30000))
            assertEquals(0, GuideSteps.secondsLeft(loaded, 60000))
            val rebound = send(loaded, SessionStarted(if (s.current == 7) Scope.EVERYTHING_NEARBY else Scope.MY_DEVICES, 200))
            assertEquals(50000, rebound.countdownEndMs)
        }
    }

    @Test fun oldPreferencesLoadAndInvalidSubStepsResetToFirst() {
        val old = mapOf("active" to 1L, "started_at_ms" to 100L, "current" to 4L, "done" to 15L, "walks" to 2L)
        val loaded = GuideSteps.load(old)
        assertEquals(4, loaded.current)
        assertEquals(0, loaded.subStep)
        assertEquals(2, loaded.walks)
        assertEquals(15, loaded.done)
        assertEquals(State(), GuideSteps.load(emptyMap()))
        for (step in 0..10) for (bad in listOf(-1L, 999L, Long.MAX_VALUE)) {
            val reset = GuideSteps.load(GuideSteps.save(at(step)) + mapOf("sub_step" to bad, "countdown_end_ms" to 10000L))
            assertEquals(step, reset.current)
            assertEquals(0, reset.subStep)
            assertEquals(0, reset.countdownEndMs)
        }
        assertEquals(0, GuideSteps.load(GuideSteps.save(at(3, 4)) + ("wizard_version" to 999L)).subStep)
        assertEquals(0, GuideSteps.load(GuideSteps.save(at(4, 2))).subStep)
    }

    @Test fun backMovesOneSubStepOrToPreviousStepsFirstSubStep() {
        for (step in 0..10) {
            for (sub in 1 until GuideSteps.subStepCount(step)) {
                val back = send(at(step, sub), Back(1000))
                assertEquals(step, back.current)
                val startSub = if (step == 4) 1 else 2
                val expected = when {
                    step == 1 && sub in 3..6 -> 2
                    step in 4..5 && sub - startSub in 1..6 -> startSub
                    else -> sub - 1
                }
                assertEquals(expected, back.subStep)
            }
            val back = send(at(step), Back(1000))
            assertEquals((step - 1).coerceAtLeast(0), back.current)
            assertEquals(0, back.subStep)
        }
        assertEquals(10, send(at(11), Back(1000)).current)
        assertEquals(0, send(at(4, 3), Back(1000)).countdownEndMs)
    }

    @Test fun invalidActionsDoNotSkipSubStepsOrStartSessions() {
        for (step in 0..10) {
            val s = at(step)
            assertEquals(s, send(s, Tap(Action.Far, 200), Tap(Action.Moving, 200)))
            if (step != 5) assertEquals(s, send(s, Tap(Action.Skip, 200)))
        }
        assertEquals(State(), send(State(), Tap(Action.Continue, 200)))
    }

    @Test fun timedWalksNeedOrderedMarkersAndBothTwentyFiveSecondHolds() {
        assertTrue(GuideSteps.qualifiesWalk(walk()))
        assertTrue(GuideSteps.qualifiesWalk(walk(duration = 51, moving = 25, far = 26)))
        for (bad in listOf(walk(moving = 24), walk(far = 46), walk(moving = 45, far = 40),
            walk(near = -1), walk(moving = 40, far = 40), walk().copy(complete = false),
            walk().copy(scope = Scope.MY_LINKS), walk().copy(markerTimesNs = mapOf("near" to 0))))
            assertFalse(bad.toString(), GuideSteps.qualifiesWalk(bad))
    }

    @Test fun preciseNanosecondsDoNotRoundAnInsufficientHoldUp() {
        val s = walk().copy(markerTimesNs = mapOf("near" to 1L, "moving" to 25_000_000_000L, "far" to 40_000_000_000L))
        assertFalse(GuideSteps.qualifiesWalk(s))
        assertFalse(GuideSteps.qualifiesWalk(walk().copy(durationNs = 64_999_999_999L)))
    }

    @Test fun legacyNinetySecondWalksStillCountWithoutNearOrMarkerTimes() {
        val legacy = SessionFinished(Scope.MY_DEVICES, 90, setOf("moving", "far"), 200)
        assertTrue(GuideSteps.qualifiesWalk(legacy))
        assertTrue(GuideSteps.qualifiesWalk(legacy.copy(durationS = 91)))
        for (bad in listOf(legacy.copy(durationS = 89), legacy.copy(plannedS = 180),
            legacy.copy(markers = setOf("far")), legacy.copy(complete = false))) assertFalse(GuideSteps.qualifiesWalk(bad))
    }

    @Test fun allPagesArePlainAndOfferOnlyAllowedDurations() {
        assertEquals(11, GuideSteps.steps.size)
        for (step in 0..10) for (sub in 0 until GuideSteps.subStepCount(step)) {
            val page = GuideSteps.page(at(step, sub))
            assertFalse(page.instruction.contains('—'))
            assertTrue(page.instruction.isNotBlank())
            (page.primary?.action as? Action.Start)?.let { assertTrue(it.seconds in setOf(90, 180, 300, 600)) }
        }
        assertFalse(GuideSteps.page(at(10)).instruction.contains("screenshot", ignoreCase = true))
    }
}
