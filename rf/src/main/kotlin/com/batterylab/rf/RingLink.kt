package com.batterylab.rf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val NS_PER_S = 1_000_000_000L

/**
 * What ring_link needs from Android (R6). Only the Android adapter touches BluetoothGatt, and it
 * may call only connect, disconnect, close and readRemoteRssi. Every call here is made with the
 * stream's lock held, so the adapter needs no locking of its own.
 */
interface RingLinkPort {
    /** Creates the adapter's own callback thread. Called once, at stream start. */
    fun open()
    fun bluetoothOn(): Boolean
    /** The connect permission (Nearby devices) is granted. */
    fun hasPermission(): Boolean
    /** A battery test is running (activeExperiment() is not null). An unreadable answer counts as running. */
    fun testRunning(): Boolean
    /** Takes RingReader's one-connection lock; false when another Ring read holds it. */
    fun tryTakeGate(): Boolean
    fun releaseGate()
    /** Starts one connection attempt. Its callbacks carry [link]. False when no attempt started. */
    fun connect(link: Int, events: RingLinkEvents): Boolean
    fun disconnect()
    fun close()
    /** Starts one RSSI read on the open link. False when it did not start. */
    fun readRssi(): Boolean
    /** Bluetooth on/off changes, delivered only between these two calls (a session-only receiver). */
    fun watchBluetooth(listener: (Boolean) -> Unit)
    fun unwatchBluetooth()
    /** Ends the callback thread. Called once, last, at stream stop. */
    fun shutdown()
}

/** Callbacks from the adapter, on its own thread. [link] says which connection attempt they belong to. */
interface RingLinkEvents {
    fun connectionChanged(link: Int, connected: Boolean, gattStatus: Int)
    fun rssi(link: Int, value: Int, gattStatus: Int)
}

/**
 * ring_link (R6, R7): the Ring's link RSSI once per second, through a small state machine.
 *
 * - Takes the Ring gate before connect and releases it exactly once, after close().
 * - With a battery test running it reports blocked_by_test and does not connect; with the gate
 *   busy it reports busy. Both check again every 5 seconds.
 * - No read before the link reports connected with GATT_SUCCESS. A connect attempt lasts at most
 *   15 seconds. On failure or a lost link: disconnect(), wait up to 2 seconds for the disconnect
 *   callback, close(), pause 10 seconds, try again while the session runs.
 * - While connected: one read per second, never two outstanding. No callback within 3 seconds is
 *   stored as timeout; three in a row count as a lost link. Android's RSSI callback carries no
 *   request id, so a timed-out read stays outstanding: no new read is issued until its late
 *   callback arrives, which is counted and ignored, never stored as a fresh sample.
 * - Stop: disconnect(), wait up to 2 seconds, close() and release the gate, on every path.
 */
class RingLinkStream(private val port: RingLinkPort, private val scheduler: Scheduler) : Stream, RingLinkEvents {
    override val id = "ring_link"
    override val kind = Kinds.RING_LINK
    override val nominalRateHz = 1.0
    override val extraColumns = listOf(GATT_STATUS)

    private enum class State { IDLE, WAITING, CONNECTING, CONNECTED, CLOSING, STOPPED }

    private val lock = Any()
    private var sink: StreamSink? = null
    private var state = State.IDLE
    /** The current connection attempt. Callbacks carrying any other number are ignored. */
    private var link = 0
    private var linkOpen = false
    private var holdsGate = false
    private var btOn = false
    private var stopped = false
    private var permissionLost = false
    /** The one pending state timer: retry, connect limit, close wait or pause. */
    private var timer: Cancellable? = null
    private var tick: Cancellable? = null
    private var readTimer: Cancellable? = null
    private var readId = 0
    private var outstanding = false
    /** The outstanding read already timed out; its callback, if it ever comes, is stale. */
    private var timedOut = false
    private var timeoutsInRow = 0
    private var ticks = 0L
    private var originNs = 0L
    private var late = 0
    private var closeLatch: CountDownLatch? = null
    private var shown = Status.STARTING

    companion object {
        const val GATT_STATUS = "gatt_status"
        const val READ_TIMEOUT = "timeout"
        const val READ_NOT_STARTED = "read_not_started"
        const val READ_FAILED = "read_failed"
        const val RETRY_NS = 5 * NS_PER_S
        const val CONNECT_LIMIT_NS = 15 * NS_PER_S
        const val CLOSE_WAIT_NS = 2 * NS_PER_S
        const val CLOSE_WAIT_MS = 2_000L
        const val PAUSE_NS = 10 * NS_PER_S
        const val READ_TIMEOUT_NS = 3 * NS_PER_S
        /** How often a connected link checks that no battery test has started. */
        const val TEST_CHECK_TICKS = 5L
    }

    override fun start(sink: StreamSink) { synchronized(lock) {
        if (this.sink != null || stopped) return
        this.sink = sink
        try {
            port.open()
            port.watchBluetooth { on -> bluetoothChanged(on) }
            btOn = port.bluetoothOn()
        } catch (e: SecurityException) {
            noPermission(); return
        } catch (e: Exception) {
            sink.failed("ring_link could not start: ${e.javaClass.simpleName}"); return
        }
        attempt()
    } }

    /** Lock held. One try at taking the gate and connecting. */
    private fun attempt() {
        if (stopped || permissionLost) return
        cancelTimer()
        state = State.WAITING
        if (sink == null) return
        if (!btOn) { state = State.IDLE; say(Status.OFF); return }
        val allowed = try { port.hasPermission() } catch (_: Exception) { false }
        if (!allowed) { noPermission(); return }
        if (port.testRunning()) {
            say(Status.BLOCKED_BY_TEST)
            timer = scheduler.schedule(RETRY_NS) { synchronized(lock) { if (state == State.WAITING) attempt() } }
            return
        }
        if (!port.tryTakeGate()) {
            say(Status.BUSY)
            timer = scheduler.schedule(RETRY_NS) { synchronized(lock) { if (state == State.WAITING) attempt() } }
            return
        }
        holdsGate = true
        link++
        state = State.CONNECTING
        say(Status.STARTING, "connecting")
        val l = link
        try {
            linkOpen = port.connect(l, this)
        } catch (e: SecurityException) {
            noPermission(); return
        } catch (e: Exception) {
            linkOpen = false
        }
        if (!linkOpen) { failLink("connect did not start"); return }
        timer = scheduler.schedule(CONNECT_LIMIT_NS) {
            synchronized(lock) { if (link == l && state == State.CONNECTING) failLink("connect timeout") }
        }
    }

    override fun connectionChanged(link: Int, connected: Boolean, gattStatus: Int) { synchronized(lock) {
        if (link != this.link) return
        if (stopped) { if (!connected) closeLatch?.countDown(); return }
        when (state) {
            State.CONNECTING ->
                if (connected && gattStatus == 0) onConnected()
                else failLink("connect failed (status $gattStatus)")
            State.CONNECTED -> if (!connected) failLink("link lost (status $gattStatus)")
            State.CLOSING -> if (!connected) finishClose()
            else -> { }
        }
    } }

    /** Lock held. */
    private fun onConnected() {
        cancelTimer()
        state = State.CONNECTED
        timeoutsInRow = 0
        outstanding = false
        timedOut = false
        say(Status.RECORDING)
        originNs = scheduler.bootNs()
        ticks = 0
        scheduleTick(link)
    }

    /** Lock held. Ticks are aligned to the moment the link came up, one per second. */
    private fun scheduleTick(l: Int) {
        ticks++
        val due = originNs + ticks * NS_PER_S - scheduler.bootNs()
        tick = scheduler.schedule(due) { synchronized(lock) { readTick(l) } }
    }

    /** Lock held. */
    private fun readTick(l: Int) {
        if (l != link || state != State.CONNECTED || stopped) return
        if (ticks % TEST_CHECK_TICKS == 0L && port.testRunning()) { failLink("battery test started"); return }
        scheduleTick(l)
        if (outstanding) return
        val started = try {
            port.readRssi()
        } catch (e: SecurityException) {
            noPermission(); return
        } catch (e: Exception) { false }
        if (!started) { row(null, READ_NOT_STARTED, null); return }
        outstanding = true
        timedOut = false
        val id = ++readId
        readTimer = scheduler.schedule(READ_TIMEOUT_NS) { synchronized(lock) { readTimeout(l, id) } }
    }

    /**
     * Lock held. The read stays outstanding, so no second read can overlap it; each further 3
     * seconds without a callback is another timeout, and the third in a row ends the link.
     */
    private fun readTimeout(l: Int, id: Int) {
        if (l != link || state != State.CONNECTED || !outstanding || id != readId) return
        timedOut = true
        row(null, READ_TIMEOUT, null)
        timeoutsInRow++
        if (timeoutsInRow >= 3) { failLink("three read timeouts"); return }
        readTimer = scheduler.schedule(READ_TIMEOUT_NS) { synchronized(lock) { readTimeout(l, id) } }
    }

    override fun rssi(link: Int, value: Int, gattStatus: Int) { synchronized(lock) {
        if (stopped) return
        if (link != this.link || state != State.CONNECTED || !outstanding || timedOut) {
            // The answer to a read that already timed out frees the link for the next read.
            if (link == this.link && state == State.CONNECTED && outstanding) {
                outstanding = false; timedOut = false
                readTimer?.cancel(); readTimer = null
            }
            late++
            say(shown, "late callbacks: $late")
            return
        }
        outstanding = false
        readTimer?.cancel(); readTimer = null
        if (gattStatus == 0) {
            timeoutsInRow = 0
            row(value, Status.OK, gattStatus)
        } else {
            row(null, READ_FAILED, gattStatus)
        }
    } }

    /** Lock held. A failed read is stored as an empty value with its status. */
    private fun row(value: Int?, status: String, gattStatus: Int?) {
        sink?.observe(Obs(scheduler.bootNs(), scheduler.wallMs(), 0, value, status,
            mapOf(GATT_STATUS to (gattStatus?.toString() ?: ""))))
    }

    /** Lock held. Hang up, wait up to 2 seconds for the disconnect callback, then close. */
    private fun failLink(reason: String) {
        cancelTimer()
        stopReads()
        state = State.CLOSING
        say(Status.NOT_CONNECTED, reason)
        if (!linkOpen) { finishClose(); return }
        try { port.disconnect() } catch (_: Exception) { }
        val l = link
        timer = scheduler.schedule(CLOSE_WAIT_NS) { synchronized(lock) { if (link == l && state == State.CLOSING) finishClose() } }
    }

    /** Lock held. Close, release the gate, then pause 10 seconds before the next try. */
    private fun finishClose() {
        cancelTimer()
        closeLink()
        if (stopped || permissionLost) return
        state = State.WAITING
        timer = scheduler.schedule(PAUSE_NS) { synchronized(lock) { if (state == State.WAITING) attempt() } }
    }

    /** Lock held. close() when a link exists, then the gate exactly once. Later callbacks for it are ignored. */
    private fun closeLink() {
        if (linkOpen) {
            linkOpen = false
            try { port.close() } catch (_: Exception) { }
        }
        link++
        if (holdsGate) {
            holdsGate = false
            try { port.releaseGate() } catch (_: Exception) { }
        }
    }

    /** Lock held. Permission gone: close at once and stop trying for this session. */
    private fun noPermission() {
        permissionLost = true
        cancelTimer()
        stopReads()
        closeLink()
        state = State.IDLE
        say(Status.PERMISSION_NEEDED)
    }

    private fun stopReads() {
        tick?.cancel(); tick = null
        readTimer?.cancel(); readTimer = null
        outstanding = false; timedOut = false
    }

    /** Lock held. Reports a status and remembers it, so a late-callback count keeps the current one. */
    private fun say(status: String, detail: String = "") {
        shown = status
        sink?.status(status, detail)
    }

    private fun cancelTimer() { timer?.cancel(); timer = null }

    /** Bluetooth off closes the link at once; Bluetooth on starts the connect loop again 10 seconds later. */
    fun bluetoothChanged(on: Boolean) { synchronized(lock) {
        if (stopped || sink == null || on == btOn) return
        btOn = on
        if (!on) {
            cancelTimer()
            stopReads()
            closeLink()
            state = State.IDLE
            say(Status.OFF)
        } else if (!permissionLost) {
            cancelTimer()
            state = State.WAITING
            timer = scheduler.schedule(PAUSE_NS) { synchronized(lock) { if (state == State.WAITING) attempt() } }
        }
    } }

    /** Safe to call twice. Returns after close() and the gate release, within about 2 seconds. */
    override fun stop() {
        val latch: CountDownLatch?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            cancelTimer()
            stopReads()
            try { port.unwatchBluetooth() } catch (_: Exception) { }
            latch = if (linkOpen) CountDownLatch(1) else null
            closeLatch = latch
            if (latch != null) try { port.disconnect() } catch (_: Exception) { latch.countDown() }
        }
        try { latch?.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        synchronized(lock) {
            closeLink()
            state = State.STOPPED
            closeLatch = null
        }
        try { port.shutdown() } catch (_: Exception) { }
    }
}

/** A stream that runs nothing and only explains why, for example ring_link with no Ring chosen. */
class IdleStream(override val id: String, override val kind: String, private val status: String,
                 private val detail: String = "") : Stream {
    override val nominalRateHz = 0.0
    override fun start(sink: StreamSink) = sink.status(status, detail)
    override fun stop() { }
}
