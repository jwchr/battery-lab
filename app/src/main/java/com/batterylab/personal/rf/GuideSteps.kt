package com.batterylab.personal.rf

import com.batterylab.rf.Scope

/** Pure wizard rules. The screen performs actions only after a tap. */
object GuideSteps {
    data class Step(val title: String, val instructions: String, val optional: Boolean = false)
    val steps = listOf(
        Step("Permissions", "Start a session and answer the permission question."),
        Step("Bluetooth off", "Turn Bluetooth off and back on during a session."),
        Step("Rotate", "Turn the phone sideways, then back upright."),
        Step("Location off", "Check Location and the Privacy dashboard."),
        Step("Three walks", "Walk away from the Ring three times."),
        Step("Optional walks", "Try three more walks with the Ring disconnected.", true),
        Step("Ring away", "Check the Ring link with the Ring far away."),
        Step("Everything nearby", "Leave the phone on a table for 5 minutes."),
        Step("Ring battery", "Look for the Ring battery on the main screen."),
        Step("End of day", "Check the Ring connection tonight."),
        Step("Finished", "You're done. Thank you.")
    )
    sealed interface Action {
        data class Start(val scope: Scope, val seconds: Int) : Action
        object Continue : Action
        object AppSettings : Action
        object LocationSettings : Action
        object PrivacyDashboard : Action
        object MainScreen : Action
        object Moving : Action
        object Far : Action
        object Yes : Action
        object No : Action
        object Skip : Action
        object Later : Action
    }
    data class Button(val label: String, val action: Action)
    data class Page(val instruction: String, val primary: Button? = null, val secondary: Button? = null)
    data class State(
        val active: Boolean = false,
        val startedAtMs: Long = 0,
        val stepStartedAtMs: Long = startedAtMs,
        val current: Int = 0,
        val subStep: Int = 0,
        val countdownEndMs: Long = 0,
        val done: Int = 0,
        val skipped: Int = 0,
        val walks: Int = 0,
        val optionalBaseline: Int = 0,
        val walkNumber: Int = 1,
        val retryWalk: Boolean = false,
        val reviewing: Boolean = false,
        val attemptStartedAtMs: Long = 0,
        val answers: Map<String, Boolean> = emptyMap(),
        val nearbyFinished: Boolean = false,
        val locationOffSession: Boolean = false,
        val todayCount: Int = 0,
        // Reattach only to a live session in this process. Never persist connection evidence.
        val runningScope: Scope? = null,
        val bluetoothOff: Boolean = false,
        val bluetoothBack: Boolean = false,
        val sessionContinued: Boolean = false,
        val locationOn: Boolean = false,
        val locationKnown: Boolean = false,
    ) {
        val finished get() = current == steps.size
        fun isDone(index: Int) = done and (1 shl index) != 0
        fun isSkipped(index: Int) = skipped and (1 shl index) != 0
    }
    sealed interface Event
    data class Tap(val action: Action, val timeMs: Long) : Event
    data class Tick(val timeMs: Long) : Event
    data class SessionStarted(val scope: Scope, val timeMs: Long) : Event
    data class SessionFinished(
        val scope: Scope, val durationS: Long, val markers: Set<String>, val timeMs: Long,
        val plannedS: Int = 90, val complete: Boolean = true,
        val markerTimesNs: Map<String, Long> = emptyMap(),
        val durationNs: Long = durationS * 1_000_000_000L,
    ) : Event
    data class BluetoothChanged(val on: Boolean, val timeMs: Long = 0) : Event
    data class LocationChanged(val on: Boolean) : Event
    object SessionContinued : Event
    object SessionEnded : Event
    object RecreatedDuringSession : Event
    object RingLinkNotConnected : Event
    data class Back(val timeMs: Long) : Event
    data class HistoryLoaded(val sessions: List<SessionFinished>, val todayCount: Int) : Event

    private val answerLabels = linkedMapOf(
        "answer_precise_prompt" to "Android asked for Precise location",
        "answer_session_kept_going" to "Bluetooth session kept going",
        "answer_privacy_listed" to "Battery Lab was in the Privacy dashboard",
        "answer_ring_not_connected" to "Ring link said Not connected",
        "answer_ring_battery" to "Ring battery number was shown",
        "answer_wearable_connected" to "Ring was still connected tonight",
    )
    fun answerSummary(s: State) = answerLabels.mapNotNull { (key, label) ->
        s.answers[key]?.let { "$label: ${if (it) "Yes" else "No"}" }
    }
    fun start(timeMs: Long) = State(active = true, startedAtMs = timeMs)
    private fun walkStart(s: State) = if (s.current == 5) 2 else 1
    private fun walkPhase(s: State) = s.subStep - walkStart(s)
    fun subStepCount(step: Int) = when (step) {
        0, 2, 7, 8 -> 2
        1 -> 7
        3 -> 6
        4 -> 8
        5 -> 9
        6 -> 3
        else -> 1
    }
    private fun nextSub(s: State, seconds: Int = 0, now: Long = 0) = s.copy(
        subStep = s.subStep + 1, countdownEndMs = if (seconds > 0) now + seconds * 1000L else 0)
    private fun advance(s: State) = s.copy(current = s.current + 1, subStep = 0, countdownEndMs = 0,
        done = s.done or (1 shl s.current), walkNumber = 1, retryWalk = false, reviewing = false, attemptStartedAtMs = 0,
        optionalBaseline = if (s.current == 4) s.walks else s.optionalBaseline,
        bluetoothOff = false, bluetoothBack = false, sessionContinued = false)
    private fun retry(s: State) = s.copy(subStep = walkStart(s), countdownEndMs = 0,
        retryWalk = true, runningScope = null, attemptStartedAtMs = 0)
    fun secondsLeft(s: State, now: Long) = ((s.countdownEndMs - now + 999) / 1000).coerceAtLeast(0)
    fun needsStop(s: State) = !s.finished && when (s.current) {
        1 -> s.subStep == 6
        4, 5 -> walkPhase(s) == 5
        else -> false
    }

    fun qualifiesWalk(s: SessionFinished): Boolean {
        if (!s.complete || s.scope != Scope.MY_DEVICES) return false
        val near = s.markerTimesNs["near"]
        val moving = s.markerTimesNs["moving"]
        val far = s.markerTimesNs["far"]
        val timed = near != null && moving != null && far != null && near >= 0 &&
            moving - near >= 25_000_000_000L && far > moving && s.durationNs - far >= 25_000_000_000L
        val legacy = s.plannedS == 90 && s.durationS >= 90 && s.markers.containsAll(setOf("moving", "far"))
        return timed || legacy
    }
    private fun qualifiesNearby(s: SessionFinished) = s.complete && s.scope == Scope.EVERYTHING_NEARBY && s.durationS >= 300

    fun page(s: State): Page {
        fun button(label: String, action: Action = Action.Continue) = Button(label, action)
        fun start(scope: Scope = Scope.MY_DEVICES, seconds: Int = 90, text: String = "Press Start.") =
            Page(text, button("Start", Action.Start(scope, seconds)))
        fun question(text: String) = Page(text, button("Yes", Action.Yes), button("No", Action.No))
        if (s.finished) return Page("You're done. Thank you.")
        if (s.current in 4..5) {
            if (s.current == 5 && s.subStep == 0) return Page(
                "Optional. Open Galaxy Wearable from your home screen and disconnect the Ring, or press Skip.",
                button("I did that"), button("Skip", Action.Skip))
            return when (walkPhase(s)) {
                -1 -> Page("Take the Ring off and put it on its charger or a table.", button("Done"))
                0 -> start(seconds = 180, text = if (s.retryWalk)
                    "That walk stopped early. Press Start to try it again." else
                    "Hold the phone next to the Ring. Press Start when you're ready.")
                1 -> Page("Stay next to the Ring.")
                2 -> Page("Good. When you're ready to walk to the far end of your apartment, press Start.",
                    button("Start walking", Action.Moving))
                3 -> Page("Walk to the far end of your apartment. Press I'm there when you arrive.", button("I'm there", Action.Far))
                4 -> Page("Stay right there.")
                5 -> Page("Good. Saving this walk.")
                else -> if (s.walkNumber < 3) Page("Walk ${s.walkNumber} done.", button("Start walk ${s.walkNumber + 1}"))
                    else Page("All three walks are done.", button("Next step"))
            }
        }
        return when (s.current) {
            0 -> if (s.subStep == 0) start(text = "Press Start. When Android asks about Nearby devices and Location, pick Approximate, then Allow.")
                else question("Did Android then ask for Precise location?")
            1 -> when (s.subStep) {
                0 -> Page("Press the button, then tap Permissions, then Location, and turn on Use precise location. Then come back here.", button("Open app settings", Action.AppSettings))
                1 -> Page("When you're back here, press I'm back.", button("I'm back"))
                2 -> start(seconds = 180)
                3 -> Page("Swipe down from the top of the screen and turn Bluetooth off. Then come back here.")
                4 -> Page("Now turn Bluetooth back on.")
                5 -> Page("Good. Wait 20 seconds.")
                else -> Page("Did the session keep going? ${if (s.answers["answer_session_kept_going"] == true) "Yes." else "No."}", button("Next"))
            }
            2 -> if (s.subStep == 0) start() else Page("Turn the phone sideways, then back upright.", button("I did it"))
            3 -> when (s.subStep) {
                0 -> Page("Press the button and turn Location off. Then come back here.", button("Open Location settings", Action.LocationSettings))
                1 -> start(text = "Press Start and read what it says.")
                2 -> Page("Press the button and turn Location back on. Then come back here.", button("Open Location settings", Action.LocationSettings))
                3 -> Page("Press the button and look for Battery Lab. Then come back here.", button("Open Privacy dashboard", Action.PrivacyDashboard))
                4 -> Page("When you're back here, press I'm back.", button("I'm back"))
                else -> question("Did Battery Lab show up there?")
            }
            6 -> when (s.subStep) {
                0 -> Page("Take the Ring far away, like another room with the door closed, or put it on its charger there. Then come back to the phone.", button("Done"))
                1 -> start(Scope.MY_LINKS)
                else -> question("Does Ring link say Not connected?")
            }
            7 -> if (s.subStep == 0) start(Scope.EVERYTHING_NEARBY, 300,
                "Press Start and leave the phone on a table. This takes 5 minutes.") else Page("Leave the phone on the table.")
            8 -> if (s.subStep == 0) Page("Make sure Ring monitoring is on, then press the button and look at the Ring battery on the main screen. Then come back.", button("Open main screen", Action.MainScreen))
                else question("Did you see a Ring battery number?")
            9 -> question("Do this tonight: open Galaxy Wearable from your home screen. Is the Ring still connected?")
            else -> Page("You're done. Thank you.", button("Finish"))
        }
    }

    fun onEvent(s: State, event: Event): State {
        if (!s.active) return s
        if (event is Back) {
            if (s.current in 4..5 && walkPhase(s) in 1..6) return s.copy(
                subStep = walkStart(s), countdownEndMs = 0, retryWalk = false, reviewing = true,
                done = s.done and (1 shl s.current).inv(), skipped = s.skipped and (1 shl s.current).inv())
            val previous = if (s.subStep > 0) s.current else (s.current - 1).coerceAtLeast(0)
            val sub = if (s.subStep > 0) s.subStep - 1 else 0
            val seconds = when {
                previous == 1 && sub == 5 -> 20
                else -> 0
            }
            return s.copy(current = previous, subStep = sub,
                countdownEndMs = if (seconds > 0) event.timeMs + seconds * 1000L else 0, done = s.done and (1 shl previous).inv(),
                skipped = s.skipped and (1 shl previous).inv(), retryWalk = false, reviewing = previous in 4..5)
        }
        if (s.finished) return s
        return when (event) {
            is Tap -> {
                if (event.action == Action.Later) return s
                if (event.action != page(s).primary?.action && event.action != page(s).secondary?.action) return s
                when (event.action) {
                    is Action.Start -> s // Only SessionStarted confirms a successful start.
                    Action.Yes, Action.No -> answer(s, event.action == Action.Yes)
                    Action.Skip -> advance(s.copy(skipped = s.skipped or (1 shl s.current)))
                    Action.Moving -> nextSub(s)
                    Action.Far -> nextSub(s, 30, event.timeMs)
                    else -> when {
                        s.current in 4..5 && walkPhase(s) == 6 -> if (s.walkNumber == 3) advance(s)
                            else s.copy(subStep = walkStart(s), walkNumber = s.walkNumber + 1, countdownEndMs = 0, retryWalk = false)
                        s.current == 3 && s.subStep in setOf(0, 2) -> s // Wait for the Location switch.
                        s.current in setOf(1, 2) && s.subStep == subStepCount(s.current) - 1 -> advance(s)
                        s.current == 10 -> advance(s)
                        else -> nextSub(s)
                    }
                }
            }
            is SessionStarted -> {
                if (event.timeMs < s.startedAtMs) return s
                val attached = s.copy(runningScope = event.scope, reviewing = false)
                val expected = page(s).primary?.action as? Action.Start
                if (expected?.scope != event.scope) return attached
                val started = attached.copy(attemptStartedAtMs = event.timeMs, retryWalk = false,
                    bluetoothOff = false, bluetoothBack = false, sessionContinued = false)
                when {
                    s.current in 4..5 -> nextSub(started, 30, event.timeMs)
                    s.current == 7 -> nextSub(started, 300, event.timeMs)
                    else -> nextSub(started)
                }
            }
            is Tick -> if (s.countdownEndMs > 0 && event.timeMs >= s.countdownEndMs) {
                if (s.current == 1 && s.subStep == 5) nextSub(s.copy(answers = s.answers +
                    ("answer_session_kept_going" to (s.runningScope == Scope.MY_DEVICES && s.bluetoothBack && s.sessionContinued))))
                else if (s.current in 4..5) nextSub(s)
                else s // Nearby finishes only after the saved session confirms its length.
            } else s
            is BluetoothChanged -> if (s.current == 1) when {
                s.subStep == 3 && !event.on -> nextSub(s.copy(bluetoothOff = s.runningScope == Scope.MY_DEVICES))
                s.subStep == 4 && event.on -> nextSub(s.copy(bluetoothBack = s.bluetoothOff && s.runningScope == Scope.MY_DEVICES), 20, event.timeMs)
                else -> s
            } else s
            is LocationChanged -> {
                val updated = s.copy(locationOn = event.on, locationKnown = true)
                if (s.current == 3 && ((s.subStep == 0 && !event.on) || (s.subStep == 2 && event.on))) nextSub(updated) else updated
            }
            SessionContinued -> if (s.current == 1 && s.subStep == 5 && s.runningScope == Scope.MY_DEVICES && s.bluetoothBack)
                s.copy(sessionContinued = true) else s
            SessionEnded -> when {
                s.current in 4..5 && walkPhase(s) in 1..4 -> retry(s)
                s.current == 7 && s.subStep == 1 -> s.copy(runningScope = null) // History decides complete or retry.
                else -> s.copy(runningScope = null)
            }
            RecreatedDuringSession -> if (s.current == 2 && s.subStep == 1 && s.runningScope == Scope.MY_DEVICES) advance(s) else s
            RingLinkNotConnected -> if (s.current == 6 && s.subStep == 2 && s.runningScope == Scope.MY_LINKS) answer(s, true) else s
            is SessionFinished -> onEvent(s, HistoryLoaded(listOf(event), s.todayCount))
            is HistoryLoaded -> {
                val sessions = event.sessions.filter { it.timeMs >= s.startedAtMs }
                val walks = sessions.count(::qualifiesWalk)
                var updated = s.copy(walks = walks, todayCount = event.todayCount,
                    nearbyFinished = sessions.any(::qualifiesNearby))
                if (s.current in 4..5) {
                    val count = (walks - if (s.current == 5) s.optionalBaseline else 0).coerceIn(0, 3)
                    when {
                        walkPhase(s) == 5 -> {
                            val attempt = sessions.firstOrNull { it.timeMs == s.attemptStartedAtMs }
                            updated = when {
                                attempt != null && qualifiesWalk(attempt) -> updated.copy(
                                    subStep = walkStart(s) + 6, countdownEndMs = 0, retryWalk = false)
                                attempt != null || s.runningScope == null -> retry(updated)
                                else -> updated
                            }
                        }
                        walkPhase(s) <= 0 && !s.reviewing -> {
                            if (count >= 3 || count >= s.walkNumber) updated = updated.copy(
                                subStep = walkStart(s) + 6, walkNumber = count.coerceAtLeast(1), countdownEndMs = 0, retryWalk = false)
                            else if (count > 0) updated = updated.copy(walkNumber = count + 1)
                        }
                    }
                }
                if (s.current == 7 && s.subStep == 1 && s.runningScope == null) {
                    updated = if (sessions.any { it.timeMs == s.attemptStartedAtMs && qualifiesNearby(it) }) advance(updated)
                        else updated.copy(subStep = 0, countdownEndMs = 0)
                }
                updated
            }
            is Back -> s
        }
    }
    private fun answer(s: State, yes: Boolean): State {
        val key = when (s.current) {
            0 -> "answer_precise_prompt"
            3 -> "answer_privacy_listed"
            6 -> "answer_ring_not_connected"
            8 -> "answer_ring_battery"
            9 -> "answer_wearable_connected"
            else -> return s
        }
        return advance(s.copy(answers = s.answers + (key to yes)))
    }

    /** Numeric progress and answers only. No identifiers, readings or connection handles. */
    fun save(s: State): Map<String, Long> = mapOf(
        "wizard_version" to 1L, "active" to if (s.active) 1L else 0L,
        "started_at_ms" to s.startedAtMs, "step_started_at_ms" to s.stepStartedAtMs,
        "current" to s.current.toLong(), "sub_step" to s.subStep.toLong(), "countdown_end_ms" to s.countdownEndMs,
        "done" to s.done.toLong(), "skipped" to s.skipped.toLong(), "walks" to s.walks.toLong(),
        "optional_baseline" to s.optionalBaseline.toLong(), "walk_number" to s.walkNumber.toLong(),
        "retry_walk" to if (s.retryWalk) 1L else 0L, "attempt_started_at_ms" to s.attemptStartedAtMs,
        "reviewing" to if (s.reviewing) 1L else 0L,
        "nearby_finished" to if (s.nearbyFinished) 1L else 0L,
        "location_off_session" to if (s.locationOffSession) 1L else 0L,
    ) + s.answers.filterKeys { it in answerLabels }.mapValues { if (it.value) 1L else 0L }

    fun load(values: Map<String, Long>): State {
        fun number(key: String) = (values[key] ?: 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val current = number("current").takeIf { it <= steps.size } ?: 0
        val sub = values["sub_step"]?.takeIf { values["wizard_version"] == 1L && it >= 0 && it < subStepCount(current) }?.toInt() ?: 0
        val end = (values["countdown_end_ms"] ?: 0).coerceAtLeast(0)
        val timed = (current == 1 && sub == 5) || (current == 7 && sub == 1) ||
            (current in 4..5 && sub - (if (current == 5) 2 else 1) in setOf(1, 4))
        val validSub = if (timed && end == 0L) 0 else sub
        return State(active = values["active"] == 1L, startedAtMs = (values["started_at_ms"] ?: 0).coerceAtLeast(0),
            stepStartedAtMs = (values["step_started_at_ms"] ?: values["started_at_ms"] ?: 0).coerceAtLeast(0),
            current = current, subStep = validSub, countdownEndMs = if (timed && validSub == sub) end else 0,
            done = number("done") and 2047, skipped = number("skipped") and 2047, walks = number("walks"),
            optionalBaseline = number("optional_baseline"), walkNumber = number("walk_number").coerceIn(1, 3),
            retryWalk = values["retry_walk"] == 1L, attemptStartedAtMs = (values["attempt_started_at_ms"] ?: 0).coerceAtLeast(0),
            reviewing = values["reviewing"] == 1L,
            nearbyFinished = values["nearby_finished"] == 1L, locationOffSession = values["location_off_session"] == 1L,
            answers = answerLabels.keys.mapNotNull { key -> values[key]?.takeIf { it in 0L..1L }?.let { key to (it == 1L) } }.toMap())
    }
}
