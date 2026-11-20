package com.batterylab.rf

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StartRequest(
    val scope: Scope,
    val provenance: Provenance,
    durationS: Int = SessionController.DEFAULT_DURATION_S,
    /** Extra session.properties keys, such as a replay's source id or a simulation seed. */
    val extraProps: Map<String, String> = emptyMap(),
    /** Built with the session's own scheduler, so simulated and replay streams share its clock. */
    val streams: (Scheduler) -> List<Stream>,
    /** Markers to add at these offsets from the start (replay). */
    val replayMarkers: List<Pair<Long, String>> = emptyList(),
) {
    /** Default 90 seconds, maximum 10 minutes. */
    val durationS: Int = durationS.coerceIn(1, SessionController.MAX_DURATION_S)
}

sealed class StartResult {
    data class Started(val id: String) : StartResult()
    data class Refused(val reason: String) : StartResult()
}

/** Called exactly once at each session's start and end; the app writes its two probe lines here. */
interface SessionHooks {
    fun started(scope: Scope, plannedS: Int)
    fun ended(scope: Scope, actualS: Int)
}

data class TracePoint(val tBootNs: Long, val rssiDbm: Int?)

data class StreamView(
    val id: String, val kind: String, val hasTrace: Boolean, val status: String, val detail: String,
    val rows: Long, val bytes: Long, val dropped: Long, val lastDbm: Int?, val lastDbmNs: Long?,
    val trace: List<TracePoint>,
)

data class Snapshot(
    val id: String, val scope: Scope, val provenance: Provenance, val imported: Boolean,
    val startNs: Long, val nowNs: Long, val durationS: Int, val running: Boolean, val stopReason: String?,
    val streams: List<StreamView>, val markers: List<Marker>,
)

/**
 * One active session per process (R10). Ends on stop, the time limit, the size limit, or the
 * screen leaving the foreground (R11); a stream error ends only that stream. Outside a session it
 * holds no scheduler, timer or stream (R12). Thread-safe: streams call their sinks from their own
 * threads, and the lock is never held while a stream is started or stopped.
 */
class SessionController(
    val store: SessionStore,
    private val schedulers: () -> Scheduler,
    private val meta: () -> Map<String, String> = { emptyMap() },
    private val hooks: SessionHooks? = null,
    private val sizeLimitBytes: Long = SIZE_LIMIT_BYTES,
) {
    companion object {
        const val DEFAULT_DURATION_S = 90
        const val MAX_DURATION_S = 600
        const val SIZE_LIMIT_BYTES = 16L * 1024 * 1024
        const val STOP_TIMEOUT_MS = 3_000L
        const val TRACE_POINTS = 1_200
        private const val SECOND_NS = 1_000_000_000L
    }

    private class Live(val id: String, val kind: String) {
        var status = Status.STARTING
        var detail = ""
        var rows = 0L
        var bytes = 0L
        var dropped = 0L
        var lastDbm: Int? = null
        var lastDbmNs: Long? = null
        val trace = ArrayDeque<TracePoint>()
        var ended = false
    }

    private inner class Active(
        val id: String, val scope: Scope, val provenance: Provenance, val durationS: Int,
        val startNs: Long, val scheduler: Scheduler, val writer: SessionWriter, val streams: List<Stream>,
    ) {
        val live = LinkedHashMap<String, Live>()
        val markers = mutableListOf<Marker>()
        val timers = mutableListOf<Cancellable>()
        var stopping = false
        var stopRequested = false
        /** Counted down once every stream's start() has returned or been skipped. */
        val startupDone = CountDownLatch(1)
    }

    private val lock = Any()
    private var active: Active? = null
    private var lastSnapshot: Snapshot? = null

    /** The most recent session to end in this process, for the result card. */
    @Volatile var lastEndedId: String? = null
        private set

    init {
        // Startup: an unfinished folder from an earlier process is marked interrupted and kept.
        store.markInterrupted(null)
    }

    val activeId: String? get() = synchronized(lock) { active?.id }
    val running: Boolean get() = synchronized(lock) { active != null }

    fun start(req: StartRequest): StartResult {
        val a: Active
        synchronized(lock) {
            if (active != null) return StartResult.Refused("A session is already running")
            val scheduler = schedulers()
            val startNs = scheduler.bootNs()
            val startWall = scheduler.wallMs()
            val id = store.newId(startWall)
            val props = linkedMapOf(
                Keys.FORMAT to Keys.FORMAT_VERSION.toString(),
                Keys.MIN_READER to Keys.READER_VERSION.toString(),
                Keys.ID to id,
                Keys.START_BOOT_NS to startNs.toString(),
                Keys.START_WALL_MS to startWall.toString(),
                Keys.END_BOOT_NS to "",
                Keys.END_WALL_MS to "",
                Keys.BOOT_COUNT to "",
                Keys.APP_VERSION to "",
                Keys.PHONE_MODEL to "",
                Keys.OS_BUILD to "",
            )
            props.putAll(meta())
            props[Keys.PROVENANCE] = req.provenance.name
            props[Keys.SCOPE] = req.scope.id
            // Milestone 1 writes no device names and no keys.
            props[Keys.IDENTIFIER_MODE] = "none"
            props[Keys.STATE] = Keys.STATE_RECORDING
            props[Keys.STOP_REASON] = ""
            props[Keys.IMPORTED] = "false"
            props[Keys.DURATION_LIMIT_S] = req.durationS.toString()
            props.putAll(req.extraProps)
            val streams: List<Stream>
            val writer: SessionWriter
            try {
                writer = SessionWriter(store.dir(id), props, sizeLimitBytes)
                streams = req.streams(scheduler)
                for (s in streams) writer.addStream(s.id, s.kind, s.unit, s.nominalRateHz, s.extraColumns, s.binary)
            } catch (e: Exception) {
                scheduler.shutdown()
                store.dir(id).deleteRecursively()
                return StartResult.Refused("Could not create the session: ${e.javaClass.simpleName}")
            }
            a = Active(id, req.scope, req.provenance, req.durationS, startNs, scheduler, writer, streams)
            streams.forEach { a.live[it.id] = Live(it.id, it.kind) }
            writer.event(startNs, startWall, EventType.SESSION, value = "start")
            active = a
        }
        try {
            store.enforceRetention(keep = setOf(a.id))
            hooks?.started(a.scope, a.durationS)
            for (s in a.streams) {
                // A stop that arrives during startup waits for this loop, then stops every stream.
                if (synchronized(lock) { a.stopping }) break
                val sink = SinkImpl(a, s)
                sink.status(Status.STARTING)
                try { s.start(sink) } catch (e: SecurityException) {
                    sink.status(Status.PERMISSION_NEEDED); sink.failed("SecurityException")
                } catch (e: Exception) { sink.failed(e.javaClass.simpleName) }
            }
            synchronized(lock) {
                if (active !== a || a.stopping) return StartResult.Started(a.id)
                a.timers += a.scheduler.schedule(a.durationS * SECOND_NS) { stopSession(a, StopReason.TIME_LIMIT) }
                scheduleTick(a)
                for ((offset, label) in req.replayMarkers) a.timers += a.scheduler.schedule(offset) { markerFor(a, label) }
            }
        } finally {
            a.startupDone.countDown()
        }
        return StartResult.Started(a.id)
    }

    private fun scheduleTick(a: Active) {
        a.timers += a.scheduler.schedule(SECOND_NS) {
            tick()
            synchronized(lock) { if (active === a && !a.stopping) scheduleTick(a) }
        }
    }

    /** Once per second: rows are flushed to disk at least this often. */
    fun tick() {
        synchronized(lock) {
            val a = active ?: return
            if (a.stopping) return
            try { a.writer.flush() } catch (_: Exception) { }
        }
    }

    /** Adds a marker at the current time. Returns false outside a session. */
    fun marker(label: String): Boolean = markerFor(null, label)

    /** With [expected] set, adds the marker only while that session is the active one. */
    private fun markerFor(expected: Active?, label: String): Boolean = synchronized(lock) {
        val a = active ?: return false
        if ((expected != null && a !== expected) || a.stopping) return false
        val now = a.scheduler.bootNs()
        if (!a.writer.event(now, a.scheduler.wallMs(), EventType.MARKER, text = label)) {
            requestStop(a, StopReason.SIZE_LIMIT); return false
        }
        a.markers += Marker(now, label)
        true
    }

    /** Ends the session. Safe to call twice and from any thread; streams get 3 seconds to stop. */
    fun stop(reason: String = StopReason.STOP) = stopSession(null, reason)

    /**
     * With [expected] set, stops only that session: a timer or request from an ended session that
     * fires late can never stop the next one.
     */
    private fun stopSession(expected: Active?, reason: String) {
        val a: Active
        synchronized(lock) {
            a = active ?: return
            if (expected != null && a !== expected) return
            if (a.stopping) return
            a.stopping = true
            a.timers.forEach { it.cancel() }
            a.timers.clear()
        }
        // Streams still starting get stopped only after their start() returns (bounded).
        try { a.startupDone.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        stopStreams(a.streams)
        var actualS = 0
        try {
            synchronized(lock) {
                val end = a.scheduler.bootNs()
                val endWall = a.scheduler.wallMs()
                actualS = ((end - a.startNs) / SECOND_NS).toInt()
                var saved = try {
                    a.live.values.filter { !it.ended }.forEach {
                        it.status = Status.ENDED
                        a.writer.event(end, endWall, EventType.STATUS, it.id, Status.ENDED)
                    }
                    a.writer.event(end, endWall, EventType.SESSION, value = "stop", text = reason)
                    true
                } catch (_: Exception) { false }
                // A failed write is kept as interrupted, never reported as complete.
                saved = try {
                    a.writer.finish(mapOf(Keys.END_BOOT_NS to end.toString(), Keys.END_WALL_MS to endWall.toString(),
                        Keys.STATE to if (saved) Keys.STATE_COMPLETE else Keys.STATE_INTERRUPTED, Keys.STOP_REASON to reason)) && saved
                } catch (_: Exception) { false }
                lastSnapshot = snapshotOf(a, end, running = false, stopReason = if (saved) reason else StopReason.INTERRUPTED)
            }
        } finally {
            synchronized(lock) {
                lastEndedId = a.id
                if (active === a) active = null
            }
            try { a.scheduler.shutdown() } catch (_: Exception) { }
        }
        hooks?.ended(a.scope, actualS)
    }

    /** Every stream stops on its own thread; the whole stop waits 3 seconds at most. */
    private fun stopStreams(streams: List<Stream>) {
        val threads = streams.map { s ->
            Thread({ try { s.stop() } catch (_: Exception) { } }, "rf-stop-${s.id}").apply { isDaemon = true; start() }
        }
        val deadline = System.nanoTime() + STOP_TIMEOUT_MS * 1_000_000
        for (t in threads) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left > 0) t.join(left)
        }
    }

    private fun requestStop(a: Active, reason: String) {
        if (a.stopRequested) return
        a.stopRequested = true
        a.scheduler.schedule(0) { stopSession(a, reason) }
    }

    /** The running session, or the last one to end in this process. */
    fun snapshot(): Snapshot? = synchronized(lock) {
        val a = active ?: return lastSnapshot
        snapshotOf(a, a.scheduler.bootNs(), running = !a.stopping, stopReason = null)
    }

    private fun snapshotOf(a: Active, now: Long, running: Boolean, stopReason: String?) = Snapshot(
        a.id, a.scope, a.provenance, false, a.startNs, now, a.durationS, running, stopReason,
        a.live.values.map {
            StreamView(it.id, it.kind, Kinds.hasTrace(it.kind), it.status, it.detail, it.rows, it.bytes, it.dropped,
                it.lastDbm, it.lastDbmNs, it.trace.toList())
        },
        a.markers.toList(),
    )

    private inner class SinkImpl(private val a: Active, private val stream: Stream) : StreamSink {
        private val live get() = a.live.getValue(stream.id)

        private fun accepting(): Boolean = active === a && !a.stopping && !live.ended

        private fun recordPoint(t: Long, dbm: Int?, ok: Boolean) {
            val l = live
            l.trace.addLast(TracePoint(t, if (ok) dbm else null))
            while (l.trace.size > TRACE_POINTS) l.trace.removeFirst()
            if (ok && dbm != null) { l.lastDbm = dbm; l.lastDbmNs = t }
        }

        override fun observe(obs: Obs) = synchronized(lock) {
            if (!accepting()) return
            val before = a.writer.totalBytes
            if (!a.writer.writeObs(stream.id, obs)) { requestStop(a, StopReason.SIZE_LIMIT); return }
            live.rows++
            live.bytes += a.writer.totalBytes - before
            recordPoint(obs.tBootNs, obs.rssiDbm, obs.status == Status.OK)
        }

        override fun binary(tBootNs: Long, tWallMs: Long, bytes: ByteArray) = synchronized(lock) {
            if (!accepting()) return
            val before = a.writer.totalBytes
            if (!a.writer.writeBinary(stream.id, tBootNs, tWallMs, bytes)) { requestStop(a, StopReason.SIZE_LIMIT); return }
            live.rows++
            live.bytes += a.writer.totalBytes - before
        }

        override fun status(status: String, detail: String) = synchronized(lock) {
            if (!accepting()) return
            if (status == live.status && detail == live.detail && status != Status.STARTING) return
            live.status = status
            live.detail = detail
            if (!a.writer.event(a.scheduler.bootNs(), a.scheduler.wallMs(), EventType.STATUS, stream.id, status, detail)) {
                requestStop(a, StopReason.SIZE_LIMIT)
            }
        }

        override fun dropped(count: Int) = synchronized(lock) {
            if (!accepting()) return
            live.dropped += count
            a.writer.dropped(stream.id, count)
        }

        override fun failed(reason: String) {
            synchronized(lock) {
                if (!accepting()) return
                a.writer.event(a.scheduler.bootNs(), a.scheduler.wallMs(), EventType.STATUS, stream.id, Status.ERROR, reason)
                live.status = Status.ERROR
                live.detail = reason
                live.ended = true
            }
            // On its own thread: the stream may be reporting from a thread its stop() joins, and a stop
            // that hangs must not hold the session's scheduler.
            Thread({ try { stream.stop() } catch (_: Exception) { } }, "rf-fail-${stream.id}").apply { isDaemon = true; start() }
        }
    }
}

/** A session folder found on disk, for the list on the screen. */
fun SessionStore.sessions(): List<SessionInfo> = ids().mapNotNull { id ->
    try { SessionReader.read(dir(id)) } catch (_: Exception) { null }
}

/** Builds a replay of [dir] (R8): same order and relative timing; SIMULATED stays SIMULATED. */
object Replay {
    fun request(dir: File): StartRequest {
        val info = SessionReader.read(dir)
        val base = info.startBootNs ?: 0L
        val tables = info.streams.associate { it.id to SessionReader.obs(dir, it.id) }
        val lastRowNs = tables.values.flatMap { t -> t.rows.mapNotNull { t.cell(it, "t_boot_ns")?.toLongOrNull() } }
            .maxOrNull()
        val lengthNs = info.durationNs ?: lastRowNs?.let { it - base } ?: 0L
        val durationS = ((lengthNs + 999_999_999L) / 1_000_000_000L).toInt().coerceIn(1, SessionController.MAX_DURATION_S)
        val provenance = if (info.provenance == Provenance.SIMULATED) Provenance.SIMULATED else Provenance.REPLAY
        val markers = SessionReader.markers(dir).map { (it.tBootNs - base).coerceAtLeast(0) to it.label }
        return StartRequest(
            scope = info.scope ?: Scope.MY_LINKS,
            provenance = provenance,
            durationS = durationS,
            extraProps = mapOf(Keys.SOURCE_ID to info.id),
            streams = { sch ->
                info.streams.map { s ->
                    val bin = java.io.File(dir, FileNames.bin(s.id)).takeIf { it.isFile }?.readBytes()
                    ReplayStream(s, tables.getValue(s.id), bin, base, sch)
                }
            },
            replayMarkers = markers,
        )
    }
}

/** Plays one recorded stream back with its original kind, columns and timing. */
class ReplayStream(
    info: StreamInfo,
    private val table: CsvTable,
    private val bin: ByteArray?,
    private val baseNs: Long,
    private val scheduler: Scheduler,
) : Stream {
    override val id = info.id
    override val kind = info.kind
    override val unit = info.unit.ifEmpty { "dBm" }
    override val nominalRateHz = info.raw["nominal_rate_hz"]?.toDoubleOrNull() ?: 0.0
    override val extraColumns = table.header.filter { it !in Columns.OBS }
    override val binary = bin != null

    @Volatile private var running = false
    private var task: Cancellable? = null
    private var index = 0
    private var startNs = 0L
    private lateinit var sink: StreamSink
    private val usesBin = table.has(Columns.BIN_OFFSET) && table.has(Columns.BIN_LENGTH)

    private fun offset(row: List<String>) =
        ((table.cell(row, "t_boot_ns")?.toLongOrNull() ?: baseNs) - baseNs).coerceAtLeast(0)

    override fun start(sink: StreamSink) {
        this.sink = sink
        running = true
        startNs = scheduler.bootNs()
        sink.status(Status.RECORDING)
        // A bin_ file with no rows pointing into it is played back whole, at the start.
        if (bin != null && !usesBin) sink.binary(startNs, scheduler.wallMs(), bin)
        next()
    }

    @Synchronized private fun next() {
        if (!running || index >= table.rows.size) return
        val due = startNs + offset(table.rows[index])
        task = scheduler.schedule(due - scheduler.bootNs()) { emitDue() }
    }

    private fun emitDue() {
        synchronized(this) {
            if (!running) return
            val now = scheduler.bootNs()
            while (index < table.rows.size && startNs + offset(table.rows[index]) <= now) emit(table.rows[index++], now)
        }
        next()
    }

    private fun emit(row: List<String>, now: Long) {
        val wall = scheduler.wallMs()
        if (usesBin && bin != null) {
            val off = table.cell(row, Columns.BIN_OFFSET)?.toIntOrNull()
            val len = table.cell(row, Columns.BIN_LENGTH)?.toIntOrNull()
            if (off != null && len != null && off >= 0 && len >= 0 && off + len <= bin.size) {
                sink.binary(now, wall, bin.copyOfRange(off, off + len)); return
            }
        }
        val extra = LinkedHashMap<String, String>()
        for (c in extraColumns) extra[c] = table.cell(row, c) ?: ""
        sink.observe(Obs(now, wall, table.cell(row, "emitter")?.toIntOrNull() ?: 0,
            table.cell(row, "rssi_dbm")?.toIntOrNull(), table.cell(row, "status") ?: "", extra))
    }

    override fun stop() {
        synchronized(this) { running = false; task?.cancel(); task = null }
    }
}

/** Seeded, deterministic stand-in for a radio (R9): the same seed and clock give the same rows. */
class SimulatedStream(
    override val id: String,
    private val seed: Long,
    private val scheduler: Scheduler,
    override val nominalRateHz: Double = 1.0,
    private val centerDbm: Int = -60,
) : Stream {
    override val kind = Kinds.SIMULATED

    @Volatile private var running = false
    private var task: Cancellable? = null
    private var n = 0L
    private var startNs = 0L
    private var level = centerDbm
    private var state = seed
    private lateinit var sink: StreamSink
    private val periodNs = (1_000_000_000.0 / nominalRateHz).toLong()

    /** SplitMix64, so the sequence never depends on a library's random implementation. */
    private fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
    private fun nextInt(bound: Int) = ((nextLong() ushr 33) % bound).toInt()

    override fun start(sink: StreamSink) {
        this.sink = sink
        running = true
        startNs = scheduler.bootNs()
        sink.status(Status.RECORDING)
        schedule()
    }

    @Synchronized private fun schedule() {
        if (!running) return
        val due = startNs + (n + 1) * periodNs
        task = scheduler.schedule(due - scheduler.bootNs()) { emit() }
    }

    private fun emit() {
        synchronized(this) {
            if (!running) return
            n++
            level = (level + nextInt(5) - 2).coerceIn(centerDbm - 12, centerDbm + 12)
            // About one read in twenty fails and is stored as an empty value with its status.
            val failed = nextInt(20) == 0
            sink.observe(Obs(scheduler.bootNs(), scheduler.wallMs(), 0, if (failed) null else level,
                if (failed) "timeout" else Status.OK))
        }
        schedule()
    }

    override fun stop() {
        synchronized(this) { running = false; task?.cancel(); task = null }
    }

    companion object {
        /** The simulated session's two streams. */
        fun pair(seed: Long, scheduler: Scheduler): List<Stream> = listOf(
            SimulatedStream("sim_near", seed, scheduler, 1.0, -55),
            SimulatedStream("sim_far", seed + 1, scheduler, 2.0, -78),
        )
    }
}
