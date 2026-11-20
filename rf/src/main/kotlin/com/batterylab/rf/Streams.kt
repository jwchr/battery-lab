package com.batterylab.rf

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Where a session's rows came from. Shown on the badge and written to session.properties. */
enum class Provenance { LIVE, REPLAY, SIMULATED;
    companion object {
        fun parse(text: String?): Provenance? = values().firstOrNull { it.name == text }
    }
}

/** Chosen before Start and fixed for the session (R27). */
enum class Scope(val id: String, val label: String) {
    MY_LINKS("my_links", "My links"),
    MY_DEVICES("my_devices", "My devices"),
    EVERYTHING_NEARBY("everything_nearby", "Everything nearby");

    companion object {
        fun parse(id: String?): Scope? = values().firstOrNull { it.id == id }
    }
}

/**
 * Stream kinds this version knows. A kind without a trace (a binary kind, or a kind this version
 * does not know) is still recorded, replayed and exported; the screen shows only its counts.
 */
object Kinds {
    const val WIFI_LINK = "wifi_link"
    const val RING_LINK = "ring_link"
    const val BLE_SCAN = "ble_scan"
    const val BLE_OTHERS = "ble_others"
    const val WIFI_SCAN = "wifi_scan"
    const val REPLAY = "replay"
    const val SIMULATED = "simulated"
    const val UNKNOWN = "unknown"

    private val withTrace = setOf(WIFI_LINK, RING_LINK, BLE_SCAN, BLE_OTHERS, WIFI_SCAN, REPLAY, SIMULATED)

    fun hasTrace(kind: String): Boolean = kind in withTrace
}

/** Stream statuses written as events. The screen turns the empty-data ones into a reason. */
object Status {
    const val STARTING = "starting"
    const val RECORDING = "recording"
    const val NO_VALUE = "no_value"
    const val NOT_CONNECTED = "not_connected"
    const val OFF = "off"
    const val PERMISSION_NEEDED = "permission_needed"
    const val PRECISE_NEEDED = "precise_needed"
    const val LOCATION_OFF = "location_off"
    const val BUSY = "busy"
    const val BLOCKED_BY_TEST = "blocked_by_test"
    const val NO_TARGET = "no_target"
    const val UNSUPPORTED = "unsupported"
    const val ERROR = "error"
    const val ENDED = "ended"

    /** Row status for a successful read. Anything else is a failed read with an empty value. */
    const val OK = "ok"
}

/**
 * One raw observation. [rssiDbm] null means a missing value and is written as an empty cell,
 * never as zero. [extra] holds the kind's optional columns (and unknown columns on replay).
 */
data class Obs(
    val tBootNs: Long,
    val tWallMs: Long,
    val emitter: Int = 0,
    val rssiDbm: Int?,
    val status: String,
    val extra: Map<String, String> = emptyMap(),
)

/** Where a stream delivers its rows. Safe to call from any thread. */
interface StreamSink {
    fun observe(obs: Obs)
    /** Appends [bytes] to bin_<stream>.dat byte for byte and records an obs row pointing at them. */
    fun binary(tBootNs: Long, tWallMs: Long, bytes: ByteArray)
    /** A status change; written as an event only when it differs from the last one. */
    fun status(status: String, detail: String = "")
    fun dropped(count: Int)
    /** Distinct other devices heard so far this session, for streams.csv (others_seen). */
    fun othersSeen(count: Int) { }
    /** A one-off event for this stream, such as a refused Wi-Fi scan request (written to events.csv). */
    fun note(value: String, text: String = "") { }
    /** A stream error. Ends this stream only; the session and other streams continue. */
    fun failed(reason: String)
}

/**
 * A source of rows. [stop] is safe to call twice and returns only when every radio handle the
 * stream holds is released, within 3 seconds.
 */
interface Stream {
    /** 1 to 24 lowercase letters, digits or underscores; names the obs_ and bin_ files. */
    val id: String
    val kind: String
    val unit: String get() = "dBm"
    val nominalRateHz: Double
    /** Optional obs columns after the six fixed ones. */
    val extraColumns: List<String> get() = emptyList()
    /** True for a stream that calls [StreamSink.binary]; its rows then carry bin_offset and bin_length. */
    val binary: Boolean get() = false
    fun start(sink: StreamSink)
    fun stop()
}

fun interface Cancellable { fun cancel() }

/** Time sources. [bootNs] is nanoseconds since boot on the phone (elapsedRealtimeNanos). */
interface Clock {
    fun bootNs(): Long
    fun wallMs(): Long
}

/** Clock plus one timer thread. Each session gets its own and shuts it down at stop. */
interface Scheduler : Clock {
    fun schedule(delayNs: Long, task: () -> Unit): Cancellable
    fun shutdown()
}

/** A real scheduler on one daemon thread. [clock] supplies the timestamps. */
class ThreadScheduler(private val clock: Clock, name: String = "rf-timer") : Scheduler {
    private val pool = ScheduledThreadPoolExecutor(1) { r -> Thread(r, name).apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    override fun bootNs() = clock.bootNs()
    override fun wallMs() = clock.wallMs()

    override fun schedule(delayNs: Long, task: () -> Unit): Cancellable {
        if (pool.isShutdown) return Cancellable { }
        val f = pool.schedule({
            try { task() } catch (_: Throwable) { }
        }, delayNs.coerceAtLeast(0), TimeUnit.NANOSECONDS)
        return Cancellable { f.cancel(false) }
    }

    override fun shutdown() { pool.shutdown() }
}

/** A JVM clock for tests and desktop use: System.nanoTime stands in for time since boot. */
object SystemClockJvm : Clock {
    override fun bootNs() = System.nanoTime()
    override fun wallMs() = System.currentTimeMillis()
}

/** Text for the two probe_events lines per session: scope and duration, nothing else. */
object ProbeText {
    fun start(scope: Scope, plannedS: Int) = "RF session start: scope=${scope.id} duration_s=$plannedS"
    fun end(scope: Scope, actualS: Int) = "RF session end: scope=${scope.id} duration_s=$actualS"
}
