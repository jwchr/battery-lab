package com.batterylab.rf

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class RfFormatException(message: String) : Exception(message)

/** session.properties keys. Readers keep any other key they find. */
object Keys {
    const val FORMAT = "format"
    const val MIN_READER = "min_reader"
    const val ID = "id"
    const val START_BOOT_NS = "start_boot_ns"
    const val START_WALL_MS = "start_wall_ms"
    const val END_BOOT_NS = "end_boot_ns"
    const val END_WALL_MS = "end_wall_ms"
    const val BOOT_COUNT = "boot_count"
    const val APP_VERSION = "app_version"
    const val PHONE_MODEL = "phone_model"
    const val OS_BUILD = "os_build"
    const val PROVENANCE = "provenance"
    const val SCOPE = "scope"
    const val IDENTIFIER_MODE = "identifier_mode"
    const val STATE = "state"
    const val STOP_REASON = "stop_reason"
    const val IMPORTED = "imported"
    const val DURATION_LIMIT_S = "duration_limit_s"
    const val SOURCE_ID = "source_id"

    const val FORMAT_VERSION = 1
    /** The newest min_reader this build can read. */
    const val READER_VERSION = 1

    /** identifier_mode: names are written only in [NAMED] mode ("Everything nearby" with names on). */
    const val MODE_NAMED = "named"
    const val MODE_NONE = "none"

    const val STATE_RECORDING = "recording"
    const val STATE_COMPLETE = "complete"
    const val STATE_INTERRUPTED = "interrupted"
}

object StopReason {
    const val STOP = "stop"
    const val TIME_LIMIT = "time_limit"
    const val SIZE_LIMIT = "size_limit"
    const val LEFT_SCREEN = "left_screen"
    const val INTERRUPTED = "interrupted"
}

object Columns {
    val OBS = listOf("seq", "t_boot_ns", "t_wall_ms", "emitter", "rssi_dbm", "status")
    val STREAMS = listOf("id", "kind", "unit", "file", "nominal_rate_hz", "rows", "bytes", "dropped",
        "change_interval_ms", "others_seen")
    val EVENTS = listOf("seq", "t_boot_ns", "t_wall_ms", "type", "stream", "value", "text")
    /** The text column of events.csv: free text, always quoted. */
    val EVENTS_QUOTED = setOf(EVENTS.indexOf("text"))
    /** Optional columns a binary stream's obs rows use to point into bin_<stream>.dat. */
    const val BIN_OFFSET = "bin_offset"
    const val BIN_LENGTH = "bin_length"
}

object EventType {
    const val SESSION = "session"
    const val STATUS = "status"
    const val MARKER = "marker"
    const val PERMISSION = "permission"
    const val DROPPED = "dropped"
    const val NOTE = "note"
}

private fun writeAtomically(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeBytes(text.toByteArray(Charsets.UTF_8))
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** Rewrites session.properties through a temporary file and a rename, keeping unknown keys. */
fun updateProperties(dir: File, change: (LinkedHashMap<String, String>) -> Unit) {
    val file = File(dir, FileNames.PROPERTIES)
    val props = Props.parse(file.readText(Charsets.UTF_8))
    change(props)
    writeAtomically(file, Props.write(props))
}

/**
 * Appends one session's rows. Not thread-safe by itself; the controller serializes calls.
 * Every byte on disk, headers and metadata included, counts against [sizeLimitBytes]; a write past
 * it is refused. [META_RESERVE_BYTES] of the limit stays free for the final metadata rewrite.
 */
class SessionWriter(val dir: File, initialProps: Map<String, String>, private val sizeLimitBytes: Long,
                    /** The most emitters.csv can ever take; kept free from the start ("Everything nearby"). */
                    private val emittersWorstCase: Long = 0) {
    private class Out(val file: File) {
        val stream: OutputStream = BufferedOutputStream(FileOutputStream(file, true), 16 * 1024)
    }

    class StreamState(val id: String, val kind: String, val unit: String, val nominalRateHz: Double,
                      val columns: List<String>) {
        var rows = 0L
        var bytes = 0L
        var dropped = 0L
        var binBytes = 0L
        var lastValue: Int? = null
        var lastChangeNs: Long? = null
        val changeIntervalsMs = ArrayList<Long>()
        var othersSeen: Int? = null
    }

    private val props = LinkedHashMap(initialProps)
    private val streams = LinkedHashMap<String, StreamState>()
    private val obsOut = HashMap<String, Out>()
    private val binOut = HashMap<String, Out>()
    private val eventsOut: Out
    private var eventSeq = 0L
    /** The current size of the two files that are replaced rather than appended. */
    private var propsBytes = 0L
    private var streamsTableBytes = 0L
    private var emittersBytes = 0L
    var totalBytes = 0L
        private set

    companion object {
        const val META_RESERVE_BYTES = 16L * 1024
    }

    init {
        dir.mkdirs()
        writeProps()
        eventsOut = Out(File(dir, FileNames.EVENTS))
        rawWrite(eventsOut, Csv.line(Columns.EVENTS))
    }

    private fun writeProps() {
        val text = Props.write(props)
        writeAtomically(File(dir, FileNames.PROPERTIES), text)
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        totalBytes += size - propsBytes
        propsBytes = size
    }

    fun streamStates(): List<StreamState> = streams.values.toList()

    /** A [binary] stream always gets the bin_offset and bin_length columns its rows point with. */
    fun addStream(id: String, kind: String, unit: String, nominalRateHz: Double, extraColumns: List<String>,
                  binary: Boolean = false) {
        require(FileNames.validStreamId(id)) { "Bad stream id" }
        require(id !in streams) { "Duplicate stream id" }
        val extra = extraColumns + (if (binary) listOf(Columns.BIN_OFFSET, Columns.BIN_LENGTH) else emptyList())
        val cols = Columns.OBS + extra.filter { it !in Columns.OBS }.distinct()
        val st = StreamState(id, kind, unit, nominalRateHz, cols)
        streams[id] = st
        val out = Out(File(dir, FileNames.obs(id)))
        obsOut[id] = out
        st.bytes += rawWrite(out, Csv.line(cols))
        writeStreamsTable()
    }

    /** Header lines. Past the limit this throws, so the session does not start. */
    private fun rawWrite(out: Out, text: String): Int {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (!fits(bytes.size)) throw java.io.IOException("The session size limit is too small for its headers")
        out.stream.write(bytes)
        totalBytes += bytes.size
        return bytes.size
    }

    /** Room kept for the final streams.csv and session.properties: 16 KB, or an eighth of a small limit. */
    private val reserve = minOf(META_RESERVE_BYTES, sizeLimitBytes / 8)

    /** emitters.csv is always counted at its worst case, so rewriting it can never pass the limit. */
    private fun fits(len: Int) = totalBytes - emittersBytes + len <= sizeLimitBytes - reserve - emittersWorstCase

    /**
     * Replaces emitters.csv. [text] is at most the worst case given at construction, which [fits]
     * has kept free, so this is never refused for size.
     */
    fun writeEmitters(text: String) {
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        require(size <= emittersWorstCase) { "emitters.csv is larger than its reserve" }
        writeAtomically(File(dir, FileNames.EMITTERS), text)
        totalBytes += size - emittersBytes
        emittersBytes = size
    }

    /**
     * Appends device lines to emitters.csv, which must already exist, before the rows that point
     * at them reach the disk. Counted inside the same worst-case reserve.
     */
    fun appendEmitters(text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(emittersBytes + bytes.size <= emittersWorstCase) { "emitters.csv is larger than its reserve" }
        val f = File(dir, FileNames.EMITTERS)
        require(f.isFile) { "emitters.csv has not been started" }
        FileOutputStream(f, true).use { it.write(bytes) }
        totalBytes += bytes.size
        emittersBytes += bytes.size
    }

    /** Appends one obs row. Returns false, writing nothing, when the size limit would be passed. */
    /** The CSV row [o] would take as the stream's next row. */
    private fun obsLine(st: StreamState, o: Obs): ByteArray = Csv.line(st.columns.map { col ->
        when (col) {
            "seq" -> (st.rows + 1).toString()
            "t_boot_ns" -> o.tBootNs.toString()
            "t_wall_ms" -> o.tWallMs.toString()
            "emitter" -> o.emitter.toString()
            "rssi_dbm" -> Num.opt(o.rssiDbm)
            "status" -> o.status
            else -> o.extra[col] ?: ""
        }
    }).toByteArray(Charsets.UTF_8)

    fun writeObs(streamId: String, o: Obs): Boolean {
        val st = streams[streamId] ?: return true
        val line = obsLine(st, o)
        if (!fits(line.size)) return false
        obsOut.getValue(streamId).stream.write(line)
        totalBytes += line.size
        st.rows++
        st.bytes += line.size
        val v = o.rssiDbm
        if (v != null) {
            if (st.lastValue != null && v != st.lastValue) {
                st.lastChangeNs?.let { st.changeIntervalsMs.add((o.tBootNs - it) / 1_000_000) }
                st.lastChangeNs = o.tBootNs
            } else if (st.lastValue == null) st.lastChangeNs = o.tBootNs
            st.lastValue = v
        }
        return true
    }

    /** Appends [bytes] to bin_<stream>.dat unchanged and records where they landed. */
    fun writeBinary(streamId: String, tBootNs: Long, tWallMs: Long, bytes: ByteArray): Boolean {
        val st = streams[streamId] ?: return true
        // Without its reference columns the payload could not be found again: count it as dropped.
        if (Columns.BIN_OFFSET !in st.columns || Columns.BIN_LENGTH !in st.columns) { st.dropped++; return true }
        val offset = st.binBytes
        val obs = Obs(tBootNs, tWallMs, 0, null, Status.OK,
            mapOf(Columns.BIN_OFFSET to offset.toString(), Columns.BIN_LENGTH to bytes.size.toString()))
        // The payload and the row that points at it go in together or not at all, so no bytes
        // land in bin_<stream>.dat without a row.
        if (!fits(bytes.size + obsLine(st, obs).size)) return false
        val out = binOut.getOrPut(streamId) { Out(File(dir, FileNames.bin(streamId))) }
        out.stream.write(bytes)
        totalBytes += bytes.size
        st.binBytes += bytes.size
        st.bytes += bytes.size
        return writeObs(streamId, obs)
    }

    fun dropped(streamId: String, count: Int) { streams[streamId]?.let { it.dropped += count } }

    fun othersSeen(streamId: String, count: Int) { streams[streamId]?.let { it.othersSeen = count } }

    fun event(tBootNs: Long, tWallMs: Long, type: String, stream: String = "", value: String = "", text: String = ""): Boolean {
        eventSeq++
        val line = Csv.line(listOf(eventSeq.toString(), tBootNs.toString(), tWallMs.toString(), type, stream, value,
            FreeText.encode(text)), Columns.EVENTS_QUOTED).toByteArray(Charsets.UTF_8)
        if (!fits(line.size)) { eventSeq--; return false }
        eventsOut.stream.write(line)
        totalBytes += line.size
        return true
    }

    fun flush() {
        eventsOut.stream.flush()
        obsOut.values.forEach { it.stream.flush() }
        binOut.values.forEach { it.stream.flush() }
    }

    private fun writeStreamsTable() {
        val sb = StringBuilder(Csv.line(Columns.STREAMS))
        for (st in streams.values) {
            val interval = st.changeIntervalsMs.sorted().let { ResultMath.nearestRank(it, 50) }
            sb.append(Csv.line(listOf(st.id, st.kind, st.unit, FileNames.obs(st.id), Num.d(st.nominalRateHz),
                st.rows.toString(), st.bytes.toString(), st.dropped.toString(), Num.opt(interval), st.othersSeen?.toString() ?: "")))
        }
        val text = sb.toString()
        writeAtomically(File(dir, FileNames.STREAMS), text)
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        totalBytes += size - streamsTableBytes
        streamsTableBytes = size
    }

    /**
     * Closes every file and rewrites streams.csv and session.properties with the end state.
     * Returns false when a data file failed to flush or close; the session is then stored as
     * interrupted instead of [endProps]'s state, because some accepted rows may be missing.
     */
    fun finish(endProps: Map<String, String>): Boolean {
        var ok = try { flush(); true } catch (_: Exception) { false }
        (obsOut.values + binOut.values + eventsOut).forEach { try { it.stream.close() } catch (_: Exception) { ok = false } }
        writeStreamsTable()
        props.putAll(endProps)
        if (!ok) {
            props[Keys.STATE] = Keys.STATE_INTERRUPTED
            props[Keys.STOP_REASON] = StopReason.INTERRUPTED
        }
        writeProps()
        return ok
    }
}

/** One stream as listed in streams.csv, or found as an obs_/bin_ file of a kind not listed. */
class StreamInfo(val id: String, val kind: String, val unit: String, val rows: Long?, val bytes: Long?,
                 val dropped: Long?, val changeIntervalMs: Long?, val raw: Map<String, String>) {
    val hasTrace get() = Kinds.hasTrace(kind)
}

data class Marker(val tBootNs: Long, val label: String)

class SessionInfo(val dir: File, val props: LinkedHashMap<String, String>, val streams: List<StreamInfo>) {
    val id get() = props[Keys.ID] ?: dir.name
    val provenance get() = Provenance.parse(props[Keys.PROVENANCE])
    val scope get() = Scope.parse(props[Keys.SCOPE])
    val imported get() = props[Keys.IMPORTED] == "true"
    val state get() = props[Keys.STATE]
    val stopReason get() = props[Keys.STOP_REASON]?.takeIf { it.isNotEmpty() }
    val startBootNs get() = props[Keys.START_BOOT_NS]?.toLongOrNull()
    val endBootNs get() = props[Keys.END_BOOT_NS]?.toLongOrNull()
    val startWallMs get() = props[Keys.START_WALL_MS]?.toLongOrNull()
    val durationNs: Long? get() { val s = startBootNs; val e = endBootNs; return if (s != null && e != null) e - s else null }
}

object SessionReader {
    /** Reads a session folder. Throws [RfFormatException] when it does not parse. */
    fun read(dir: File): SessionInfo {
        val propFile = File(dir, FileNames.PROPERTIES)
        if (!propFile.isFile) throw RfFormatException("The session has no session.properties")
        val props = Props.parse(propFile.readText(Charsets.UTF_8))
        val format = props[Keys.FORMAT]?.toIntOrNull() ?: throw RfFormatException("The session has no format number")
        val minReader = props[Keys.MIN_READER]?.toIntOrNull() ?: format
        if (minReader > Keys.READER_VERSION) throw RfFormatException("The session needs a newer Battery Lab (reader $minReader)")
        if (props[Keys.START_BOOT_NS]?.toLongOrNull() == null) throw RfFormatException("The session has no start time")
        val listed = LinkedHashMap<String, StreamInfo>()
        val streamsFile = File(dir, FileNames.STREAMS)
        if (streamsFile.isFile) {
            val t = Csv.parse(streamsFile.readText(Charsets.UTF_8))
            if (!t.has("id") || !t.has("kind")) throw RfFormatException("streams.csv has no id or kind column")
            for (row in t.rows) {
                val id = t.cell(row, "id") ?: continue
                if (!FileNames.validStreamId(id)) throw RfFormatException("streams.csv has a bad stream id")
                listed[id] = StreamInfo(id, t.cell(row, "kind")!!.ifEmpty { Kinds.UNKNOWN }, t.cell(row, "unit") ?: "",
                    t.cell(row, "rows")?.toLongOrNull(), t.cell(row, "bytes")?.toLongOrNull(),
                    t.cell(row, "dropped")?.toLongOrNull(), t.cell(row, "change_interval_ms")?.toLongOrNull(), t.map(row))
            }
        }
        val files = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        for (name in files) {
            val id = FileNames.streamOfObs(name) ?: FileNames.streamOfBin(name) ?: continue
            if (id !in listed) listed[id] = StreamInfo(id, Kinds.UNKNOWN, "", null, null, null, null, emptyMap())
        }
        val eventsFile = File(dir, FileNames.EVENTS)
        if (eventsFile.isFile) Csv.parse(eventsFile.readText(Charsets.UTF_8))
        return SessionInfo(dir, props, listed.values.toList())
    }

    fun obs(dir: File, streamId: String): CsvTable {
        val f = File(dir, FileNames.obs(streamId))
        return if (f.isFile) Csv.parse(f.readText(Charsets.UTF_8)) else CsvTable(Columns.OBS, emptyList())
    }

    fun events(dir: File): CsvTable {
        val f = File(dir, FileNames.EVENTS)
        return if (f.isFile) Csv.parse(f.readText(Charsets.UTF_8)) else CsvTable(Columns.EVENTS, emptyList())
    }

    fun markers(dir: File): List<Marker> {
        val t = events(dir)
        return t.rows.filter { t.cell(it, "type") == EventType.MARKER }.mapNotNull { row ->
            val ns = t.cell(row, "t_boot_ns")?.toLongOrNull() ?: return@mapNotNull null
            Marker(ns, FreeText.decode(t.cell(row, "text") ?: ""))
        }.sortedBy { it.tBootNs }
    }
}

/** The folder rf/ under the app's private files: rf/sessions/<id>/ plus a staging area for imports. */
class SessionStore(val root: File) {
    val sessionsDir = File(root, "sessions")
    val stagingDir = File(root, "staging")
    /** R17: the install key and the pins live beside the sessions, never inside one. */
    val installKey = InstallKey(File(root, "install.key"))
    val pins = PinStore(File(root, "pins.csv"))

    companion object {
        const val MAX_SESSIONS = 100
        const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
        private val sessionId = Regex("[0-9]{13}(_[0-9]{1,6})?")
    }

    fun ids(): List<String> = sessionsDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun dir(id: String) = File(sessionsDir, id)

    /** Ids sort in creation order: zero-padded wall-clock milliseconds, plus a suffix on a clash. */
    fun newId(wallMs: Long): String {
        val base = String.format(java.util.Locale.ROOT, "%013d", wallMs)
        var id = base
        var n = 1
        while (dir(id).exists()) id = base + "_" + n++
        return id
    }

    fun sizeOf(id: String): Long = dir(id).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Keeps at most [maxCount] sessions and [maxBytes] in all, removing the oldest first. Sessions in
     * [keep] (the new one and any recording in progress) are never removed.
     */
    fun enforceRetention(keep: Set<String>, maxCount: Int = MAX_SESSIONS, maxBytes: Long = MAX_TOTAL_BYTES): List<String> {
        val ids = ids().toMutableList()
        val sizes = ids.associateWith { sizeOf(it) }.toMutableMap()
        val evicted = mutableListOf<String>()
        while (ids.size > maxCount || (sizes.values.sum() > maxBytes && ids.size > 1)) {
            val victim = ids.firstOrNull { it !in keep } ?: break
            dir(victim).deleteRecursively()
            ids.remove(victim); sizes.remove(victim); evicted.add(victim)
        }
        return evicted
    }

    /** Marks every unfinished folder except [activeId] as interrupted. Keeps them. */
    fun markInterrupted(activeId: String?): Int {
        var n = 0
        for (id in ids()) {
            if (id == activeId) continue
            val f = File(dir(id), FileNames.PROPERTIES)
            if (!f.isFile) continue
            val props = try { Props.parse(f.readText(Charsets.UTF_8)) } catch (_: Exception) { continue }
            if (props[Keys.STATE] == Keys.STATE_RECORDING) {
                updateProperties(dir(id)) {
                    it[Keys.STATE] = Keys.STATE_INTERRUPTED
                    it[Keys.STOP_REASON] = StopReason.INTERRUPTED
                }
                n++
            }
        }
        return n
    }

    fun delete(id: String, activeId: String?): Boolean {
        if (id == activeId || !sessionId.matches(id)) return false
        return dir(id).deleteRecursively()
    }

    /**
     * Deletes every session but the active one, then destroys the install key of R17 and with it
     * every pin, so later keys match nothing from before.
     */
    private val staleKeysFile = File(root, "stale_keys_session")

    /**
     * Delete all. A session still recording keeps device keys made with the old install key, so
     * that session is remembered and its devices cannot be pinned (a pin would never match again).
     */
    fun deleteAll(activeId: String?) {
        ids().filter { it != activeId }.forEach { dir(it).deleteRecursively() }
        installKey.destroy()
        pins.clear()
        if (activeId != null) try { staleKeysFile.writeText(activeId) } catch (_: Exception) { }
        else staleKeysFile.delete()
    }

    /** True when [id]'s device keys came from an install key that Delete all has since replaced. */
    fun keysStale(id: String?): Boolean = id != null &&
        try { staleKeysFile.isFile && staleKeysFile.readText().trim() == id } catch (_: Exception) { true }
}
