package com.batterylab.personal.rf

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.batterylab.personal.Background
import com.batterylab.personal.Palette
import com.batterylab.personal.StyledActivity
import com.batterylab.rf.FileNames
import com.batterylab.rf.ImportResult
import com.batterylab.rf.Keys
import com.batterylab.rf.Kinds
import com.batterylab.rf.Props
import com.batterylab.rf.Provenance
import com.batterylab.rf.Replay
import com.batterylab.rf.ResultMath
import com.batterylab.rf.Scope
import com.batterylab.rf.SessionArchive
import com.batterylab.rf.SessionController
import com.batterylab.rf.SessionResult
import com.batterylab.rf.SimulatedStream
import com.batterylab.rf.StartRequest
import com.batterylab.rf.StartResult
import com.batterylab.rf.Status
import com.batterylab.rf.StopReason
import com.batterylab.rf.StreamView
import com.batterylab.rf.TracePoint
import com.batterylab.rf.WindowStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RF Lab: pick a scope, press Start, and watch signal strength in dBm for up to 10 minutes.
 * The session lives in [RfRuntime], outside this Activity, so rotation keeps it running; leaving
 * the screen ends it. The screen stays on only while a session runs.
 */
class RfLabActivity : StyledActivity() {
    private lateinit var rf: SessionController
    private var durationS = SessionController.DEFAULT_DURATION_S
    private var liveBox: LinearLayout? = null
    private var resultId: String? = null
    private var result: SessionResult? = null
    private var loadingResult: String? = null
    private var pendingExportId: String? = null
    private var wasRunning = false
    /** Between onStart and onStop. A replay prepared in the background starts only while visible. */
    private var visible = false
    private var showAllSessions = false
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { onTick(); main.postDelayed(this, 500) }
    }

    private val EXPORT_ZIP = 201
    private val IMPORT_ZIP = 202
    private val durations = listOf(90 to "90 s", 180 to "3 min", 300 to "5 min", 600 to "10 min")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rf = RfRuntime.controller(this)
        durationS = savedInstanceState?.getInt("durationS", durationS) ?: durationS
        pendingExportId = savedInstanceState?.getString("exportId")
        resultId = savedInstanceState?.getString("resultId") ?: rf.lastEndedId
        setUpPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("durationS", durationS)
        pendingExportId?.let { outState.putString("exportId", it) }
        resultId?.let { outState.putString("resultId", it) }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onResume() {
        super.onResume()
        // A session that ended while the screen was paused still gets its result card.
        val running = rf.running
        if (wasRunning && !running) { resultId = rf.lastEndedId; result = null }
        wasRunning = running
        render()
        main.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresh)
    }

    override fun onStop() {
        super.onStop()
        visible = false
        // Rotation keeps the session; leaving the screen ends it (R11), off the main thread.
        if (!isChangingConfigurations && rf.running) Thread({ rf.stop(StopReason.LEFT_SCREEN) }, "rf-leave").start()
        if (!isChangingConfigurations) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun syncScreenOn(running: Boolean) {
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onTick() {
        val running = rf.running
        syncScreenOn(running)
        if (running) fillLive()
        if (wasRunning && !running) { resultId = rf.lastEndedId; result = null; render() }
        wasRunning = running
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun render() {
        val oldScroll = scroll.scrollY
        body.removeAllViews()
        liveBox = null
        section("RF Lab", "Signal strength in dBm", "Watch your own links for up to 10 minutes. " +
            "Sessions stay on this phone in their own files; the battery history and backups do not change.")
        if (rf.running) renderLive() else renderSetup()
        renderResult()
        renderSessions()
        scroll.post { scroll.scrollTo(0, oldScroll) }
    }

    private fun selectable(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
        chip(text, if (selected) Palette.navy else if (enabled) Palette.track else Palette.hairline).apply {
            if (!enabled) setTextColor(Palette.quiet)
            if (selected) typeface = medium
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun renderSetup() {
        section("New session", "Choose a scope", "The scope is fixed once the session starts.").apply {
            addView(selectable("My links", selected = true) { })
            addView(label("Your Wi-Fi link now. The Ring link arrives in the next build.", 12.5f))
            addView(selectable("My devices: coming in a later build", selected = false, enabled = false) {
                toast("Scanning arrives in a later build")
            })
            addView(selectable("Everything nearby: coming in a later build", selected = false, enabled = false) {
                toast("Scanning arrives in a later build")
            })
            addView(label("Length", 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, 0) })
            addView(chipRow(*durations.map { (s, text) ->
                selectable(text, selected = s == durationS) { durationS = s; render() }
            }.toTypedArray()))
            button("Start", primary = true) { startLive() }
            button("Start a simulated session") { startSimulated() }
        }
    }

    private fun started(r: StartResult) {
        if (r is StartResult.Refused) toast(r.reason)
        else { resultId = null; result = null }
        wasRunning = rf.running
        render()
    }

    private fun startLive() {
        val app = applicationContext
        started(rf.start(StartRequest(Scope.MY_LINKS, Provenance.LIVE, durationS,
            streams = { listOf(WifiLinkStream(app)) })))
    }

    private fun startSimulated() {
        val seed = System.currentTimeMillis()
        started(rf.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, durationS, mapOf("seed" to seed.toString()),
            { SimulatedStream.pair(seed, it) })))
    }

    private fun startReplay(id: String) {
        val dir = rf.store.dir(id)
        Background.io.execute {
            val req = try { Replay.request(dir) } catch (e: Exception) { null }
            Background.onMain {
                if (isDestroyed || !visible) return@onMain
                if (req == null) toast("This session could not be read") else started(rf.start(req))
            }
        }
    }

    private fun badges(provenance: Provenance?, scope: Scope?, imported: Boolean): View {
        val chips = mutableListOf<View>(chip(provenance?.name ?: "UNKNOWN", when (provenance) {
            Provenance.LIVE -> Palette.green; Provenance.REPLAY -> Palette.phone; else -> Palette.amber
        }), chip(scope?.label ?: "Unknown scope", Palette.navy))
        if (imported) chips += chip("IMPORTED", Palette.quiet)
        return chipRow(*chips.toTypedArray())
    }

    private fun renderLive() {
        val snap = rf.snapshot() ?: return
        section("Session", "Recording").apply {
            addView(badges(snap.provenance, snap.scope, snap.imported))
            liveBox = LinearLayout(this@RfLabActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(liveBox)
            if (snap.scope == Scope.MY_LINKS && snap.provenance == Provenance.LIVE) {
                addView(label("Ring link: coming in the next build.", 12.5f))
            }
            button("Stop", primary = true) { Thread({ rf.stop() }, "rf-stop").start() }
        }
        section("Markers", null, "Tap when you change what you are doing. A marker window runs to the next " +
            "marker or the end of the session.").apply {
            for (l in listOf("near", "moving", "far")) button("Mark: $l") { mark(l) }
            button("Mark with your own label") { customMarker() }
        }
        fillLive()
    }

    private fun mark(label: String) {
        if (rf.marker(label)) toast("Marked: $label at ${clock(System.currentTimeMillis())}")
    }

    private fun customMarker() {
        val edit = EditText(this).apply { hint = "Label"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Add a marker").setView(edit)
            .setPositiveButton("Add") { _, _ -> edit.text.toString().trim().takeIf { it.isNotEmpty() }?.let { mark(it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun fillLive() {
        val box = liveBox ?: return
        val snap = rf.snapshot() ?: return
        box.removeAllViews()
        val elapsed = ((snap.nowNs - snap.startNs) / 1_000_000_000L).coerceAtLeast(0)
        box.addView(label("$elapsed s of ${snap.durationS} s · ${snap.markers.size} " +
            (if (snap.markers.size == 1) "marker" else "markers"), 13f))
        for (s in snap.streams) addStream(box, s, snap.nowNs)
    }

    private fun streamName(id: String, kind: String) = when (kind) {
        Kinds.WIFI_LINK -> "Wi-Fi link"
        Kinds.RING_LINK -> "Ring link"
        else -> id
    }

    private fun addStream(box: LinearLayout, s: StreamView, nowNs: Long) {
        box.addView(label(streamName(s.id, s.kind), 15f, ink, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        val value = s.lastDbm?.let { dbm ->
            val age = (nowNs - (s.lastDbmNs ?: nowNs)) / 1e9
            "$dbm dBm · ${String.format(Locale.US, "%.1f", age)} s old"
        } ?: "No value yet"
        if (s.hasTrace) {
            box.addView(label(value, 22f, ink, bold = true))
            val reason = emptyReason(s.status).takeIf { s.trace.none { it.rssiDbm != null } }
            box.addView(TraceView(this, s.trace, nowNs, reason), LinearLayout.LayoutParams(-1, dp(96)))
        } else {
            box.addView(label("No trace for this kind of stream.", 13f))
        }
        box.addView(label("${statusText(s.status)} · ${s.rows} rows · ${kb(s.bytes)}" +
            (if (s.dropped > 0) " · ${s.dropped} dropped" else ""), 12f))
    }

    private fun statusText(status: String) = when (status) {
        Status.RECORDING -> "Recording"
        Status.STARTING -> "Starting"
        Status.ENDED -> "Ended"
        else -> emptyReason(status)
    }

    /** R22: why the trace is empty. */
    private fun emptyReason(status: String) = when (status) {
        Status.OFF -> "Off"
        Status.PERMISSION_NEEDED -> "No permission"
        Status.PRECISE_NEEDED -> "Precise location needed"
        Status.LOCATION_OFF -> "Location off"
        Status.NOT_CONNECTED -> "Not connected"
        Status.BUSY -> "Busy"
        Status.BLOCKED_BY_TEST -> "Blocked by a battery test"
        Status.NO_VALUE -> "Connected, but Android gave no signal value"
        Status.UNSUPPORTED -> "Needs Android 10 or later"
        Status.ERROR -> "Stopped after an error"
        Status.STARTING -> "Starting"
        else -> "Waiting for the first reading"
    }

    private fun kb(bytes: Long) = if (bytes < 1024) "$bytes B" else String.format(Locale.US, "%.1f kB", bytes / 1024.0)
    private fun clock(ms: Long) = SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(ms))
    private fun date(ms: Long) = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))

    // Result card (R30)

    private fun renderResult() {
        val id = resultId ?: return
        if (rf.running) return
        val r = result
        if (r == null || r.info.dir.name != id) {
            if (loadingResult != id) {
                loadingResult = id
                val dir = rf.store.dir(id)
                Background.io.execute {
                    val computed = try { ResultMath.compute(dir) } catch (_: Exception) { null }
                    Background.onMain {
                        loadingResult = null
                        if (isDestroyed || resultId != id) return@onMain
                        if (computed == null) { resultId = null; toast("That session could not be read") } else result = computed
                        render()
                    }
                }
            }
            section("Result", "Working it out…")
            return
        }
        val info = r.info
        section("Result", "Session result", tag = info.stopReason?.let { stopText(it) }).apply {
            addView(badges(info.provenance, info.scope, info.imported))
            val length = info.durationNs?.let { "${it / 1_000_000_000L} s" } ?: "unknown length"
            addView(label("${info.startWallMs?.let { date(it) } ?: ""} · $length", 13f))
            for (s in r.streams) {
                addView(label(streamName(s.stream.id, s.stream.kind), 15f, ink, bold = true).apply { setPadding(0, dp(10), 0, 0) })
                addView(label(windowLine("Whole session", s.whole), 12.5f))
                for ((w, st) in s.windows) addView(label(windowLine("From \"${w.label}\"", st), 12.5f))
                s.g0?.let { addView(label("G0 ${it.signal} test, this session: ${if (it.pass) "pass" else "not passed"}. " +
                    it.explanation + ".", 12.5f, ink)) }
            }
            if (r.streams.none { it.stream.kind == Kinds.RING_LINK || it.stream.kind == Kinds.BLE_SCAN }) {
                addView(label("G0 Ring tests: this session has no Ring link or Bluetooth scan stream. " +
                    "They arrive in the next build. G0 as a whole needs three sessions.", 12.5f))
            }
        }
    }

    private fun stopText(reason: String) = when (reason) {
        StopReason.STOP -> "Stopped"
        StopReason.TIME_LIMIT -> "Time limit"
        StopReason.SIZE_LIMIT -> "Size limit"
        StopReason.LEFT_SCREEN -> "Left the screen"
        StopReason.INTERRUPTED -> "Interrupted"
        else -> reason
    }

    private fun windowLine(name: String, st: WindowStats?): String {
        if (st == null) return "$name: no rows"
        val pct = String.format(Locale.US, "%.1f", st.successShare * 100)
        val rate = String.format(Locale.US, "%.2f", st.meanRowsPerSecond)
        val bucket = st.medianRowsPerBucket?.let { "median $it per second" } ?: "under one full second"
        val signal = st.medianDbm?.let { "median $it dBm (10th to 90th percentile: ${st.p10Dbm} to ${st.p90Dbm} dBm)" }
            ?: "no successful reads"
        return "$name: ${st.rows} rows, $pct% read OK, $rate rows per second, $bucket, $signal"
    }

    // Saved sessions, export and import (R15, R16)

    private fun renderSessions() {
        val store = rf.store
        val ids = store.ids().reversed()
        section("Saved sessions", if (ids.isEmpty()) "None yet" else "${ids.size} on this phone",
            "Up to ${com.batterylab.rf.SessionStore.MAX_SESSIONS} sessions or 200 MB; the oldest go first.").apply {
            for (id in if (showAllSessions) ids else ids.take(20)) {
                val props = try { Props.parse(File(store.dir(id), FileNames.PROPERTIES).readText()) } catch (_: Exception) { null }
                val start = props?.get(Keys.START_WALL_MS)?.toLongOrNull()
                val len = props?.let { p ->
                    val s = p[Keys.START_BOOT_NS]?.toLongOrNull(); val e = p[Keys.END_BOOT_NS]?.toLongOrNull()
                    if (s != null && e != null) "${(e - s) / 1_000_000_000L} s" else null
                } ?: props?.get(Keys.STATE) ?: "unreadable"
                val prov = props?.get(Keys.PROVENANCE) ?: "?"
                val scope = Scope.parse(props?.get(Keys.SCOPE))?.label ?: "?"
                val imported = if (props?.get(Keys.IMPORTED) == "true") " · IMPORTED" else ""
                addView(makeButton("${start?.let { date(it) } ?: id} · $prov · $scope · $len$imported · ${kb(store.sizeOf(id))}") {
                    sessionMenu(id)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            if (ids.size > 20 && !showAllSessions) button("Show ${ids.size - 20} older sessions") { showAllSessions = true; render() }
            button("Import a session (.zip)") { pickImport() }
            if (ids.isNotEmpty()) button("Delete all sessions") { confirmDeleteAll() }
        }
    }

    private fun sessionMenu(id: String) {
        AlertDialog.Builder(this).setItems(arrayOf("Show result", "Replay", "Export (.zip)", "Delete")) { _, which ->
            when (which) {
                0 -> { resultId = id; result = null; render() }
                1 -> startReplay(id)
                2 -> pickExport(id)
                3 -> {
                    if (rf.store.delete(id, rf.activeId)) { if (resultId == id) resultId = null; render() }
                    else toast("A running session cannot be deleted")
                }
            }
        }.show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this).setTitle("Delete all RF Lab sessions?")
            .setMessage("This removes every saved session on this phone. Your battery history is not touched.")
            .setPositiveButton("Delete all") { _, _ -> rf.store.deleteAll(rf.activeId); resultId = null; render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickExport(id: String) {
        pendingExportId = id
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "rf-session-$id.zip"), EXPORT_ZIP)
    }

    private fun pickImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")),
            IMPORT_ZIP)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val resolver = contentResolver
        if (requestCode == EXPORT_ZIP) {
            val id = pendingExportId ?: return
            pendingExportId = null
            val dir = rf.store.dir(id)
            Background.io.execute {
                val ok = try {
                    resolver.openOutputStream(uri, "wt")?.use { SessionArchive.export(dir, it) } != null
                } catch (_: Exception) { false }
                Background.onMain { toast(if (ok) "Session exported" else "Export failed") }
            }
        } else if (requestCode == IMPORT_ZIP) {
            val store = rf.store
            Background.io.execute {
                val r = try {
                    resolver.openInputStream(uri)?.use { SessionArchive.import(it, store, System.currentTimeMillis(), activeId = { rf.activeId }) }
                        ?: ImportResult.Rejected("The file could not be opened")
                } catch (_: Exception) { ImportResult.Rejected("The file could not be opened") }
                Background.onMain {
                    when (r) {
                        is ImportResult.Imported -> { toast("Session imported"); resultId = r.id; result = null }
                        is ImportResult.Rejected -> toast("Not imported: ${r.reason}")
                    }
                    if (!isDestroyed) render()
                }
            }
        }
    }
}

/** A hand-drawn trace of dBm over the last two minutes. With no data it states why (R22). */
class TraceView(context: Context, private val points: List<TracePoint>, private val nowNs: Long,
                private val emptyReason: String?) : View(context) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.track }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Palette.navy; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.quiet; textSize = 11 * d }
    private val spanNs = 120_000_000_000L

    init { contentDescription = emptyReason ?: "Signal trace in dBm" }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 8 * d, 8 * d, bg)
        val values = points.mapNotNull { it.rssiDbm }
        if (values.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyReason ?: "No readings yet", w / 2, h / 2 + text.textSize / 3, text)
            return
        }
        var lo = values.min() - 3
        var hi = values.max() + 3
        if (hi - lo < 20) { val mid = (hi + lo) / 2; lo = mid - 10; hi = mid + 10 }
        val top = 8 * d; val bottom = h - 8 * d
        fun x(t: Long) = w * (1f - (nowNs - t).toFloat() / spanNs)
        fun y(v: Int) = bottom - (bottom - top) * (v - lo) / (hi - lo).toFloat()
        val path = Path()
        var open = false
        for (p in points) {
            if (nowNs - p.tBootNs > spanNs) continue
            val v = p.rssiDbm
            if (v == null) { open = false; continue }
            if (open) path.lineTo(x(p.tBootNs), y(v)) else { path.moveTo(x(p.tBootNs), y(v)); open = true }
        }
        canvas.drawPath(path, line)
        // A lone reading shows as a dot.
        if (values.size == 1) points.last { it.rssiDbm != null }.let {
            canvas.drawCircle(x(it.tBootNs), y(it.rssiDbm!!), 2.5f * d, line.apply { style = Paint.Style.FILL })
            line.style = Paint.Style.STROKE
        }
        text.textAlign = Paint.Align.LEFT
        text.color = Color.argb(200, Color.red(Palette.quiet), Color.green(Palette.quiet), Color.blue(Palette.quiet))
        canvas.drawText("$hi dBm", 6 * d, top + text.textSize, text)
        canvas.drawText("$lo dBm", 6 * d, bottom - 2 * d, text)
    }
}
