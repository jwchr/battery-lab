package com.batterylab.personal.rf

import com.batterylab.rf.Scope

/** Text and observation rules only. This class cannot operate a radio, session or permission. */
object GuideSteps {
    data class Step(val title: String, val instructions: String, val optional: Boolean = false)

    val steps = listOf(
        Step("Permissions (D12)", "Pick My devices and tap Start. When Android asks for Nearby devices and Location, choose Approximate first, then allow. Note whether it then asks for Precise."),
        Step("Bluetooth off (D4)", "Allow Precise location in Android settings. Start My devices. Halfway through, turn Bluetooth off for about 15 seconds, then back on. Check that the session continues."),
        Step("Rotate (D13)", "Start My devices and rotate the phone while the session runs."),
        Step("Location off (D12)", "Turn Location off. Start My devices and see what it says. Turn Location back on. Then open Settings > Security and privacy > Privacy dashboard and check what it lists for Battery Lab. Come back and tap I did it."),
        Step("Gate G0: three walks", "Run three My devices sessions of 90 seconds with the Ring on your finger. Each time: tap Mark: near and hold the phone at arm's length for 30 seconds. Tap Mark: moving and walk to the far end of the apartment. Tap Mark: far and stay until it ends. Screenshot each result card."),
        Step("Optional: another walk set", "Disconnect the Ring in Galaxy Wearable yourself. Do three more 90-second My devices walks with the same near, moving and far markers. Screenshot each result card. You can skip this set.", optional = true),
        Step("Ring away (D5)", "Put the Ring on its charger or out of range. Start My links. Check that the Ring link says Not connected, or tap I did it."),
        Step("Everything nearby, 5 minutes (D14)", "Pick Everything nearby and 5 min. Tap Start and let it run for at least 5 minutes."),
        Step("Ring battery (D7)", "With Ring monitoring on and no RF Lab session running, check that the Ring battery reads on the main screen. Then come back and tap I did it."),
        Step("End of day (D8, D10)", "After about ten sessions today, check that Galaxy Wearable still shows the Ring connected. Then tap I did it."),
        Step("Screenshots (D9)", "Take screenshots of the badges, each scope, empty states and a result card. Tap I did it when you have them.")
    )

    fun completion(index: Int): String = listOf(
        "a My devices session starts, or you tap I did it.",
        "Bluetooth goes off and back on during My devices, and that session continues.",
        "the screen is recreated by rotation during My devices.",
        "My devices runs with Location off, Location is back on, and you tap I did it after checking the dashboard.",
        "three finished 90-second My devices sessions have near, moving and far markers since this guide started.",
        "three more matching walks finish, or you tap Skip.",
        "the Ring link says Not connected during My links, or you tap I did it.",
        "an Everything nearby session of at least 5 minutes finishes.",
        "you tap I did it after checking the main screen.",
        "you tap I did it after checking Galaxy Wearable.",
        "you tap I did it after taking the screenshots."
    )[index]

    data class State(
        val active: Boolean = false,
        val startedAtMs: Long = 0,
        val current: Int = 0,
        val done: Int = 0,
        val skipped: Int = 0,
        val walks: Int = 0,
        val optionalBaseline: Int = 0,
        val nearbyFinished: Boolean = false,
        val locationOffSession: Boolean = false,
        val todayCount: Int = 0,
        // Session-local evidence is deliberately not saved. A killed process must not join two sessions.
        val runningScope: Scope? = null,
        val bluetoothOff: Boolean = false,
        val bluetoothBack: Boolean = false,
        val locationOn: Boolean = false,
        val locationKnown: Boolean = false,
    ) {
        val finished get() = current == steps.size
        fun isDone(index: Int) = done and (1 shl index) != 0
        fun isSkipped(index: Int) = skipped and (1 shl index) != 0
    }

    sealed interface Event
    data class SessionStarted(val scope: Scope, val timeMs: Long) : Event
    data class SessionFinished(
        val scope: Scope, val durationS: Long, val markers: Set<String>, val timeMs: Long,
        val plannedS: Int = 90, val complete: Boolean = true,
    ) : Event
    data class BluetoothChanged(val on: Boolean) : Event
    data class LocationChanged(val on: Boolean) : Event
    object SessionContinued : Event
    object SessionEnded : Event
    object RecreatedDuringSession : Event
    object RingLinkNotConnected : Event
    object ManualDone : Event
    object Skip : Event
    object Back : Event
    /** Absolute totals from local live session files, so reopening cannot count a walk twice. */
    data class HistoryLoaded(val sessions: List<SessionFinished>, val todayCount: Int) : Event

    fun start(timeMs: Long) = State(active = true, startedAtMs = timeMs)

    fun qualifiesWalk(session: SessionFinished) = session.complete && session.scope == Scope.MY_DEVICES &&
        session.plannedS == 90 && session.durationS >= 90 && session.markers.containsAll(setOf("near", "moving", "far"))

    fun canManuallyFinish(state: State) = !state.finished && when (state.current) {
        0, 6, 8, 9, 10 -> true
        3 -> state.locationOffSession && state.locationOn
        else -> false
    }

    fun onEvent(state: State, event: Event): State {
        if (!state.active) return state
        if (event == Back) {
            val previous = (state.current - 1).coerceAtLeast(0)
            return state.copy(current = previous, done = state.done and (1 shl previous).inv(),
                skipped = state.skipped and (1 shl previous).inv(), bluetoothOff = false, bluetoothBack = false)
        }
        if (state.finished) return state
        var next = when (event) {
            is SessionStarted -> {
                if (event.timeMs < state.startedAtMs) return state
                val s = state.copy(runningScope = event.scope, bluetoothOff = false, bluetoothBack = false,
                    locationOffSession = state.locationOffSession ||
                        (state.current == 3 && event.scope == Scope.MY_DEVICES && state.locationKnown && !state.locationOn))
                if (state.current == 0 && event.scope == Scope.MY_DEVICES) advance(s) else s
            }
            is BluetoothChanged -> if (state.current == 1 && state.runningScope == Scope.MY_DEVICES) {
                state.copy(bluetoothOff = state.bluetoothOff || !event.on,
                    bluetoothBack = event.on && state.bluetoothOff)
            } else state
            SessionContinued -> if (state.current == 1 && state.runningScope == Scope.MY_DEVICES &&
                state.bluetoothOff && state.bluetoothBack) advance(state) else state
            SessionEnded -> state.copy(runningScope = null, bluetoothOff = false, bluetoothBack = false)
            is LocationChanged -> state.copy(locationOn = event.on, locationKnown = true, locationOffSession = state.locationOffSession ||
                (state.current == 3 && !event.on && state.runningScope == Scope.MY_DEVICES))
            RecreatedDuringSession -> if (state.current == 2 && state.runningScope == Scope.MY_DEVICES) advance(state) else state
            RingLinkNotConnected -> if (state.current == 6 && state.runningScope == Scope.MY_LINKS) advance(state) else state
            is SessionFinished -> {
                if (event.timeMs < state.startedAtMs) return state
                state.copy(runningScope = null, bluetoothOff = false, bluetoothBack = false,
                    walks = state.walks + if (qualifiesWalk(event)) 1 else 0,
                    nearbyFinished = state.nearbyFinished || qualifiesNearby(event))
            }
            is HistoryLoaded -> {
                val sessions = event.sessions.filter { it.timeMs >= state.startedAtMs }
                state.copy(walks = sessions.count(::qualifiesWalk), nearbyFinished = sessions.any(::qualifiesNearby),
                    todayCount = event.todayCount)
            }
            ManualDone -> if (canManuallyFinish(state)) advance(state) else state
            Skip -> if (steps[state.current].optional) advance(state.copy(skipped = state.skipped or (1 shl state.current))) else state
            Back -> state
        }
        // Earlier qualifying sessions still count when their step becomes current.
        if (event is SessionFinished || event is HistoryLoaded || next.current > state.current) {
            while (when (next.current) {
                4 -> next.walks >= 3
                5 -> next.walks - next.optionalBaseline >= 3
                7 -> next.nearbyFinished
                else -> false
            }) next = advance(next)
        }
        return next
    }

    private fun qualifiesNearby(s: SessionFinished) = s.complete && s.scope == Scope.EVERYTHING_NEARBY && s.durationS >= 300

    private fun advance(s: State): State = s.copy(current = s.current + 1, done = s.done or (1 shl s.current),
        optionalBaseline = if (s.current == 4) s.walks else s.optionalBaseline,
        bluetoothOff = false, bluetoothBack = false)

    fun progress(s: State): String = when (s.current) {
        0 -> "Waiting for My devices to start, or tap I did it."
        1 -> if (s.bluetoothBack) "Bluetooth is back on. Checking that the session continues." else if (s.bluetoothOff)
            "Bluetooth went off. Turn it back on while this session runs." else "Waiting for Bluetooth to go off and back on during My devices."
        2 -> "Waiting for rotation during My devices."
        3 -> when {
            !s.locationOffSession -> "Waiting for a My devices session with Location off."
            !s.locationOn -> "Location off was observed. Turn Location back on."
            else -> "Location is back on. Check the Privacy dashboard, then tap I did it."
        }
        4 -> "Walk ${(s.walks + 1).coerceAtMost(3)} of 3. ${s.walks.coerceAtMost(3)} finished."
        5 -> "Walk ${(s.walks - s.optionalBaseline + 1).coerceIn(1, 3)} of 3. ${(s.walks - s.optionalBaseline).coerceIn(0, 3)} finished."
        6 -> "Waiting for Not connected during My links, or tap I did it."
        7 -> "Waiting for a finished Everything nearby session of at least 5 minutes."
        9 -> "${s.todayCount} sessions today. Aim for about 10."
        else -> "Tap I did it after this check."
    }

    /** Only numeric progress and flags. No identifiers, markers, scope, readings or session handles. */
    fun save(s: State): Map<String, Long> = mapOf(
        "active" to if (s.active) 1L else 0L, "started_at_ms" to s.startedAtMs,
        "current" to s.current.toLong(), "done" to s.done.toLong(), "skipped" to s.skipped.toLong(),
        "walks" to s.walks.toLong(), "optional_baseline" to s.optionalBaseline.toLong(),
        "nearby_finished" to if (s.nearbyFinished) 1L else 0L,
        "location_off_session" to if (s.locationOffSession) 1L else 0L,
    )

    fun load(values: Map<String, Long>): State {
        fun number(key: String) = (values[key] ?: 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        return State(active = values["active"] == 1L, startedAtMs = (values["started_at_ms"] ?: 0).coerceAtLeast(0),
            current = number("current").coerceAtMost(steps.size), done = number("done") and 2047,
            skipped = number("skipped") and 2047, walks = number("walks"), optionalBaseline = number("optional_baseline"),
            nearbyFinished = values["nearby_finished"] == 1L, locationOffSession = values["location_off_session"] == 1L)
    }
}
