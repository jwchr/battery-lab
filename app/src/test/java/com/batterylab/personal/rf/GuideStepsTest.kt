package com.batterylab.personal.rf

import com.batterylab.personal.rf.GuideSteps.State
import com.batterylab.personal.rf.GuideSteps.Event
import com.batterylab.personal.rf.GuideSteps.SessionStarted
import com.batterylab.personal.rf.GuideSteps.SessionFinished
import com.batterylab.personal.rf.GuideSteps.BluetoothChanged
import com.batterylab.personal.rf.GuideSteps.LocationChanged
import com.batterylab.personal.rf.GuideSteps.SessionContinued
import com.batterylab.personal.rf.GuideSteps.SessionEnded
import com.batterylab.personal.rf.GuideSteps.RecreatedDuringSession
import com.batterylab.personal.rf.GuideSteps.RingLinkNotConnected
import com.batterylab.personal.rf.GuideSteps.ManualDone
import com.batterylab.personal.rf.GuideSteps.Skip
import com.batterylab.personal.rf.GuideSteps.Back
import com.batterylab.personal.rf.GuideSteps.HistoryLoaded
import com.batterylab.rf.Scope
import org.junit.Assert.*
import org.junit.Test

class GuideStepsTest {
    private val markers = setOf("near", "moving", "far")
    private fun at(step: Int) = GuideSteps.start(100).copy(current = step)
    private fun send(s: State, vararg events: Event) = events.fold(s, GuideSteps::onEvent)
    private fun walk(seconds: Long = 90, scope: Scope = Scope.MY_DEVICES, labels: Set<String> = markers,
                     time: Long = 200, planned: Int = 90, complete: Boolean = true) =
        SessionFinished(scope, seconds, labels, time, planned, complete)

    @Test fun everyStepOffersItsExpectedActions() {
        val devices = GuideSteps.Action.Start(Scope.MY_DEVICES, 90)
        val expected = listOf(
            listOf(devices),
            listOf(GuideSteps.Action.AppSettings, devices),
            listOf(devices),
            listOf(GuideSteps.Action.LocationSettings, devices, GuideSteps.Action.PrivacyDashboard),
            listOf(devices, GuideSteps.Action.Markers),
            listOf(GuideSteps.Action.BluetoothSettings, devices, GuideSteps.Action.Markers),
            listOf(GuideSteps.Action.Start(Scope.MY_LINKS, 90)),
            listOf(GuideSteps.Action.Start(Scope.EVERYTHING_NEARBY, 300)),
            listOf(GuideSteps.Action.MainScreen),
            listOf(GuideSteps.Action.BluetoothSettings),
            emptyList(),
        )
        assertEquals(GuideSteps.steps.size, expected.size)
        expected.forEachIndexed { index, actions -> assertEquals("Step $index", actions, GuideSteps.actions(index)) }
        assertTrue(GuideSteps.actions(10).isEmpty())
    }

    @Test fun startActionsSupportEachStepsCompletionRule() {
        for (index in 0..7) {
            val action = GuideSteps.actions(index).filterIsInstance<GuideSteps.Action.Start>().single()
            assertTrue("Step $index uses an offered duration", action.seconds in setOf(90, 180, 300, 600))
            val started = send(at(index), LocationChanged(false), SessionStarted(action.scope, 200))
            val finished = SessionFinished(action.scope, action.seconds.toLong(), markers, 300, action.seconds)
            val completed = when (index) {
                0 -> started
                1 -> send(started, BluetoothChanged(false), BluetoothChanged(true), SessionContinued)
                2 -> send(started, RecreatedDuringSession)
                3 -> send(started, LocationChanged(true), ManualDone)
                4, 5 -> {
                    assertTrue("Step $index start qualifies as a walk", GuideSteps.qualifiesWalk(finished))
                    send(started, finished, finished, finished)
                }
                6 -> send(started, RingLinkNotConnected)
                else -> send(started, finished)
            }
            assertEquals("Step $index can finish with its Start action", index + 1, completed.current)
        }
    }

    @Test fun permissionsNeedsMyDevicesStartOrManual() {
        val initial = at(0)
        assertEquals(0, send(initial, SessionStarted(Scope.MY_LINKS, 200)).current)
        assertEquals(0, send(initial, SessionStarted(Scope.EVERYTHING_NEARBY, 200)).current)
        assertEquals(0, send(initial, SessionStarted(Scope.MY_DEVICES, 99)).current)
        assertEquals(0, send(initial, walk()).current)
        assertEquals(1, send(initial, SessionStarted(Scope.MY_DEVICES, 200)).current)
        assertEquals(1, send(initial, ManualDone).current)
    }

    @Test fun bluetoothNeedsOffThenOnAndContinuationInOneMyDevicesSession() {
        val initial = at(1)
        assertEquals(1, send(initial, BluetoothChanged(false), BluetoothChanged(true), SessionContinued).current)
        assertEquals(1, send(initial, SessionStarted(Scope.MY_LINKS, 200), BluetoothChanged(false),
            BluetoothChanged(true), SessionContinued).current)
        val running = send(initial, SessionStarted(Scope.MY_DEVICES, 200))
        assertEquals(1, send(running, BluetoothChanged(true), SessionContinued).current)
        assertEquals(1, send(running, BluetoothChanged(false), SessionContinued, ManualDone, Skip).current)
        val restored = send(running, BluetoothChanged(false), BluetoothChanged(true))
        assertEquals(1, restored.current)
        assertEquals(2, send(restored, SessionContinued).current)
        assertEquals(1, send(restored, SessionEnded, SessionContinued).current)
        assertEquals(1, send(restored, SessionStarted(Scope.MY_DEVICES, 300), SessionContinued).current)
        assertEquals(1, send(restored, BluetoothChanged(false), SessionContinued).current)
    }

    @Test fun rotationRequiresRunningMyDevices() {
        assertEquals(2, send(at(2), RecreatedDuringSession, ManualDone).current)
        assertEquals(2, send(at(2), SessionStarted(Scope.MY_LINKS, 200), RecreatedDuringSession).current)
        assertEquals(3, send(at(2), SessionStarted(Scope.MY_DEVICES, 200), RecreatedDuringSession).current)
        assertEquals(2, send(at(2), SessionStarted(Scope.MY_DEVICES, 200), SessionEnded, RecreatedDuringSession).current)
    }

    @Test fun locationNeedsObservedOffDuringMyDevicesThenOnAndManual() {
        val initial = at(3)
        assertEquals(3, send(initial, ManualDone, Skip, LocationChanged(true), ManualDone).current)
        assertEquals(3, send(initial, SessionStarted(Scope.MY_DEVICES, 200), LocationChanged(true), ManualDone).current)
        assertEquals(3, send(initial, LocationChanged(false), SessionStarted(Scope.MY_LINKS, 200),
            LocationChanged(true), ManualDone).current)
        val off = send(initial, LocationChanged(false), SessionStarted(Scope.MY_DEVICES, 200))
        assertTrue(off.locationOffSession)
        assertEquals(3, send(off, ManualDone).current)
        val restored = send(off, SessionEnded, LocationChanged(true))
        assertEquals(3, restored.current)
        assertEquals(4, send(restored, ManualDone).current)
        val during = send(initial, LocationChanged(true), SessionStarted(Scope.MY_DEVICES, 200), LocationChanged(false))
        assertTrue(during.locationOffSession)
    }

    @Test fun walkNeedsMovingAndFarButNearIsOptional() {
        assertTrue(GuideSteps.qualifiesWalk(walk()))
        assertTrue(GuideSteps.qualifiesWalk(walk(labels = setOf("moving", "far"))))
        assertTrue(GuideSteps.qualifiesWalk(walk(91, labels = setOf("moving", "far"))))
        for (bad in listOf(walk(labels = setOf("near", "far")), walk(labels = setOf("near", "moving")),
            walk(89), walk(complete = false), walk(planned = 180), walk(scope = Scope.MY_LINKS))) {
            assertFalse(GuideSteps.qualifiesWalk(bad))
        }
    }

    @Test fun threeCompleteNinetySecondWalksAdvanceGateWithoutNear() {
        var state = at(4)
        for (bad in listOf(walk(60), walk(scope = Scope.MY_LINKS), walk(scope = Scope.EVERYTHING_NEARBY),
            walk(labels = setOf("near", "far")), walk(time = 99), walk(180, planned = 180), walk(complete = false))) {
            state = send(state, bad)
            assertEquals(0, state.walks)
            assertEquals(4, state.current)
        }
        state = send(state, Skip, walk(labels = setOf("moving", "far")))
        assertEquals(1, state.walks)
        assertEquals("1 of 3 walks counted", GuideSteps.progress(state))
        state = send(state, walk(labels = setOf("moving", "far")))
        assertEquals(4, state.current)
        state = send(state, walk())
        assertEquals(5, state.current)
        assertEquals(3, state.optionalBaseline)
        assertTrue(state.isDone(4))
        assertTrue(GuideSteps.progress(state).contains("Walk 1 of 3"))
        assertEquals(6, send(state, walk(), walk(), walk()).current)
    }

    @Test fun walkGateCanBeFinishedManually() {
        assertTrue(GuideSteps.canManuallyFinish(at(4)))
        val completed = send(at(4).copy(walks = 1), ManualDone)
        assertEquals(5, completed.current)
        assertEquals(1, completed.optionalBaseline)
        assertTrue(completed.isDone(4))
    }

    @Test fun walkProgressExplainsTheLastUncountedMyDevicesSession() {
        val initial = at(4)
        assertEquals("0 of 3 walks counted", GuideSteps.progress(initial))
        val cases = listOf(
            walk(labels = setOf("moving")) to "Last walk had no Mark: far",
            walk(labels = setOf("far")) to "Last walk had no Mark: moving",
            walk(89) to "Last walk ended early",
            walk(complete = false) to "Last walk ended early",
            walk(180, planned = 180) to "Last walk was not set to 90 seconds",
        )
        for ((session, reason) in cases) {
            val state = send(initial, session)
            assertEquals("0 of 3 walks counted. $reason", GuideSteps.progress(state))
            assertEquals(GuideSteps.progress(state), GuideSteps.progress(send(state, walk(scope = Scope.MY_LINKS))))
            assertEquals("1 of 3 walks counted", GuideSteps.progress(send(state, walk(time = 201))))
        }
    }

    @Test fun historyProgressUsesTheLatestMyDevicesSessionInThisStep() {
        val initial = at(4).copy(stepStartedAtMs = 150)
        val old = walk(time = 140, labels = setOf("moving"))
        assertEquals("0 of 3 walks counted", GuideSteps.progress(send(initial, HistoryLoaded(listOf(old), 1))))
        assertEquals("0 of 3 walks counted", GuideSteps.progress(send(initial, old)))
        val latest = walk(time = 200, labels = setOf("moving"))
        val history = HistoryLoaded(listOf(latest, walk(time = 180), old, walk(time = 220, scope = Scope.MY_LINKS)), 4)
        val state = send(initial, history)
        assertEquals("1 of 3 walks counted. Last walk had no Mark: far", GuideSteps.progress(state))
        val restored = GuideSteps.load(GuideSteps.save(state))
        assertEquals(150, restored.stepStartedAtMs)
        assertNull(restored.lastWalk)
        assertEquals(GuideSteps.progress(state), GuideSteps.progress(send(restored, history)))
        assertEquals("1 of 3 walks counted", GuideSteps.progress(send(state, HistoryLoaded(listOf(walk(time = 180)), 1))))
    }

    @Test fun historyIsAbsoluteAndWalksSinceGuideStartCountOnArrival() {
        val history = HistoryLoaded(listOf(walk(time = 99), walk(time = 101), walk(time = 102), walk(time = 103)), 10)
        val first = send(at(4), history)
        assertEquals(3, first.walks)
        assertEquals(5, first.current)
        assertEquals(first, send(first, history, history))
        val early = send(at(3).copy(locationOffSession = true), LocationChanged(true), history, ManualDone)
        assertEquals(5, early.current)
        assertEquals(3, early.optionalBaseline)
        val six = history.copy(sessions = history.sessions + listOf(walk(time = 104), walk(time = 105), walk(time = 106)))
        assertEquals(6, send(first, six).current)
        assertEquals(10, first.todayCount)
        assertEquals(0, send(at(4).copy(walks = 2), HistoryLoaded(emptyList(), 0)).walks)
    }

    @Test fun backToCompletedWalkGateStaysThereOnUnrelatedEvents() {
        val completed = send(at(4), walk(), walk(), walk())
        assertEquals(5, completed.current)
        assertEquals(3, completed.walks)
        var state = send(completed, Back)
        assertEquals(4, state.current)
        for (event in listOf(SessionContinued, LocationChanged(true), SessionEnded)) {
            state = send(state, event)
            assertEquals(4, state.current)
        }
        assertEquals(3, send(state, Back).current)
    }

    @Test fun backToCompletedOptionalWalksStaysThereOnUnrelatedEvents() {
        val completed = send(at(4), walk(), walk(), walk(), walk(), walk(), walk())
        assertEquals(6, completed.current)
        assertEquals(6, completed.walks)
        var state = send(completed, Back)
        assertEquals(5, state.current)
        for (event in listOf(SessionContinued, LocationChanged(true), SessionEnded)) {
            state = send(state, event)
            assertEquals(5, state.current)
        }
        assertEquals(4, send(state, Back).current)
    }

    @Test fun ringAwayRequiresMyLinksNotConnectedOrManual() {
        assertEquals(6, send(at(6), RingLinkNotConnected).current)
        assertEquals(6, send(at(6), SessionStarted(Scope.MY_DEVICES, 200), RingLinkNotConnected).current)
        assertEquals(6, send(at(6), SessionStarted(Scope.MY_LINKS, 200), SessionEnded, RingLinkNotConnected).current)
        assertEquals(7, send(at(6), SessionStarted(Scope.MY_LINKS, 200), RingLinkNotConnected).current)
        assertEquals(7, send(at(6), ManualDone).current)
    }

    @Test fun nearbyNeedsCompleteFiveMinuteEverythingNearby() {
        assertEquals(7, send(at(7), walk(600), walk(299, Scope.EVERYTHING_NEARBY),
            walk(300, Scope.EVERYTHING_NEARBY, complete = false), ManualDone, Skip).current)
        assertEquals(8, send(at(7), walk(300, Scope.EVERYTHING_NEARBY)).current)
        assertEquals(8, send(at(7), walk(600, Scope.EVERYTHING_NEARBY)).current)
        val earlier = send(at(6), walk(300, Scope.EVERYTHING_NEARBY), ManualDone)
        assertEquals(8, earlier.current)
    }

    @Test fun backToCompletedNearbyStepStaysThereOnUnrelatedEvents() {
        val completed = send(at(7), walk(300, Scope.EVERYTHING_NEARBY))
        assertEquals(8, completed.current)
        assertTrue(completed.nearbyFinished)
        var state = send(completed, Back)
        assertEquals(7, state.current)
        for (event in listOf(SessionContinued, LocationChanged(true), SessionEnded)) {
            state = send(state, event)
            assertEquals(7, state.current)
        }
        assertEquals(6, send(state, Back).current)
    }

    @Test fun manualAndSkipAreLimitedAndBackReopensPreviousStep() {
        for (i in listOf(1, 2, 3, 5, 7)) assertEquals(i, send(at(i), ManualDone).current)
        for (i in 0..10) if (i != 5) assertEquals(i, send(at(i), Skip).current)
        val skipped = send(at(5), Skip)
        assertEquals(6, skipped.current)
        assertTrue(skipped.isSkipped(5))
        assertTrue(skipped.isDone(5))
        val back = send(skipped, Back)
        assertEquals(5, back.current)
        assertFalse(back.isDone(5))
        assertFalse(back.isSkipped(5))
        assertEquals(0, send(at(0), Back).current)
        var final = at(8)
        for (i in 9..11) {
            final = send(final, ManualDone)
            assertEquals(i, final.current)
        }
        assertTrue(final.finished)
        assertEquals(final, send(final, Skip, ManualDone, RecreatedDuringSession))
        assertEquals(10, send(final, Back).current)
        assertEquals(State(), send(State(), ManualDone, SessionStarted(Scope.MY_DEVICES, 200)))
    }

    @Test fun progressSurvivesSaveLoadButRunningEvidenceDoesNot() {
        val s = at(5).copy(done = 31, skipped = 0, walks = 4, optionalBaseline = 3,
            nearbyFinished = true, locationOffSession = true, todayCount = 9, runningScope = Scope.MY_DEVICES,
            bluetoothOff = true, bluetoothBack = true, locationOn = true, locationKnown = true)
        val saved = GuideSteps.save(s)
        val loaded = GuideSteps.load(saved)
        assertEquals(saved, GuideSteps.save(loaded))
        assertEquals(5, loaded.current)
        assertEquals(4, loaded.walks)
        assertTrue(loaded.locationOffSession)
        assertNull(loaded.runningScope)
        assertFalse(loaded.bluetoothOff)
        assertFalse(loaded.bluetoothBack)
        assertFalse(loaded.locationKnown)
        assertEquals(0, loaded.todayCount)
        assertEquals(6, send(loaded, Skip).current)
        assertEquals(State(), GuideSteps.load(emptyMap()))
        val interruptedBluetooth = send(at(1), SessionStarted(Scope.MY_DEVICES, 200), BluetoothChanged(false))
        assertEquals(1, send(GuideSteps.load(GuideSteps.save(interruptedBluetooth)),
            SessionStarted(Scope.MY_DEVICES, 300), BluetoothChanged(true), SessionContinued).current)
    }

    @Test fun instructionsStayPlainAndAllElevenChecksAreListed() {
        assertEquals(11, GuideSteps.steps.size)
        assertEquals(listOf(5), GuideSteps.steps.indices.filter { GuideSteps.steps[it].optional })
        for (i in listOf(5, 9)) {
            assertTrue(GuideSteps.steps[i].instructions.contains("Galaxy Wearable"))
            assertTrue(GuideSteps.steps[i].instructions.contains("(open it from your home screen)"))
        }
        GuideSteps.steps.forEachIndexed { i, step ->
            assertFalse(step.title.contains('—'))
            assertFalse(step.instructions.contains('—'))
            assertTrue(GuideSteps.completion(i).isNotBlank())
        }
    }
}
