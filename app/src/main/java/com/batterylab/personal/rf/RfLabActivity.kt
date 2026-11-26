package com.batterylab.personal.rf

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.batterylab.personal.Background
import com.batterylab.personal.MainActivity
import com.batterylab.personal.Palette
import com.batterylab.personal.PollScheduler
import com.batterylab.personal.StyledActivity
import com.batterylab.rf.BleScanHub
import com.batterylab.rf.DeviceView
import com.batterylab.rf.EmitterFile
import com.batterylab.rf.EmitterTable
import com.batterylab.rf.FileNames
import com.batterylab.rf.IdleStream
import com.batterylab.rf.ImportResult
import com.batterylab.rf.Keys
import com.batterylab.rf.Kinds
import com.batterylab.rf.NearbyDefaults
import com.batterylab.rf.PinMatcher
import com.batterylab.rf.PinStore
import com.batterylab.rf.Props
import com.batterylab.rf.Provenance
import com.batterylab.rf.Replay
import com.batterylab.rf.ResultMath
import com.batterylab.rf.RingLinkStream
import com.batterylab.rf.Scheduler
import com.batterylab.rf.Scope
import com.batterylab.rf.SessionArchive
import com.batterylab.rf.SessionController
import com.batterylab.rf.SessionResult
import com.batterylab.rf.SimulatedStream
import com.batterylab.rf.StartRequest
import com.batterylab.rf.StartResult
import com.batterylab.rf.Status
import com.batterylab.rf.StopReason
import com.batterylab.rf.Stream
import com.batterylab.rf.StreamView
import com.batterylab.rf.TracePoint
import com.batterylab.rf.WifiScanStream
import com.batterylab.rf.WindowStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

/**
 * RF Lab: pick a scope, press Start, and watch signal strength in dBm for up to 10 minutes.
 * The session lives in [RfRuntime], outside this Activity, so rotation keeps it running; leaving
 * the screen ends it. The screen stays on only while a session runs.
 */
class RfLabActivity : StyledActivity() {
    private lateinit var rf: SessionController
    private var durationS = SessionController.DEFAULT_DURATION_S
    private var scope = Scope.MY_LINKS
    private var liveBox: LinearLayout? = null
    private var resultId: String? = null
    private var result: SessionResult? = null
    private var loadingResult: String? = null
    private var pendingExportId: String? = null
    private var wasRunning = false
    private var stopRequested = false
    private var markNearOnStart = false
    private lateinit var guidePrefs: android.content.SharedPreferences
    private var guide = GuideSteps.State()
    private var showAllRfLab = false
    private var guideSessionStatus: android.widget.TextView? = null
    private var guideLiveStatus: android.widget.TextView? = null
    private var guideList: LinearLayout? = null
    private var guideDialog: AlertDialog? = null
    private var guideSessionId: String? = null
    private var guideEndedId: String? = null
    private var guideHistoryGeneration = 0
    private var guideDay = 0L
    private var createdOrientation = 0
    private val guideBluetooth by lazy { BluetoothWatch(this) }
    /** Between onStart and onStop. A replay prepared in the background starts only while visible. */
    private var visible = false
    private var showAllSessions = false
    @Volatile private var sessionsGeneration = 0
    /** The last saved-session list read in the background, and whether it was the "show all" list. */
    private var lastSessions: Pair<SavedSessions.Snapshot, Boolean>? = null
    private val TAG = "BatteryLabRf"
    /** "Everything nearby": store names this session (on by default, Jeff's decision of Nov 19). */
    private var storeNames = NearbyDefaults.STORE_NAMES
    private var strongestFirst = true
    private var showAllDevices = false
    private var deviceBox: LinearLayout? = null
    private var resultDevices: List<DeviceView> = emptyList()
    private var pendingExportNames = false
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { onTick(); main.postDelayed(this, 500) }
    }

    private val EXPORT_ZIP = 201
    private val IMPORT_ZIP = 202
    private val RF_PERMISSIONS = 203
    private val durations = listOf(90 to "90 s", 180 to "3 min", 300 to "5 min", 600 to "10 min")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rf = RfRuntime.controller(this)
        wasRunning = rf.running
        guidePrefs = getSharedPreferences("rf_guide", MODE_PRIVATE)
        guide = GuideSteps.load(guidePrefs.all.mapNotNull { (key, value) ->
            (value as? Long)?.let { key to it }
        }.toMap())
        createdOrientation = resources.configuration.orientation
        durationS = savedInstanceState?.getInt("durationS", durationS) ?: durationS
        scope = Scope.parse(savedInstanceState?.getString("scope"))
            ?.takeIf { Build.VERSION.SDK_INT >= 31 || it == Scope.MY_LINKS } ?: scope
        storeNames = savedInstanceState?.getBoolean("storeNames", storeNames) ?: storeNames
        pendingExportId = savedInstanceState?.getString("exportId")
        pendingExportNames = savedInstanceState?.getBoolean("exportNames", false) ?: false
        markNearOnStart = savedInstanceState?.getBoolean("markNearOnStart", false) ?: false
        showAllRfLab = savedInstanceState?.getBoolean("showAllRfLab", false) ?: false
        resultId = savedInstanceState?.getString("resultId") ?: rf.lastEndedId
        setUpPage()
        observeGuideSession()
        if (savedInstanceState != null && savedInstanceState.getString("guideSession") == rf.activeId && rf.activeId != null) {
            // Restore session-local evidence only if this very session is still alive in this process.
            guide = guide.copy(bluetoothOff = savedInstanceState.getBoolean("guideBluetoothOff"),
                bluetoothBack = savedInstanceState.getBoolean("guideBluetoothBack"),
                sessionContinued = savedInstanceState.getBoolean("guideSessionContinued"))
            if (savedInstanceState.getInt("guideOrientation") != createdOrientation)
                guideEvent(GuideSteps.RecreatedDuringSession)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("durationS", durationS)
        outState.putString("scope", scope.id)
        outState.putBoolean("storeNames", storeNames)
        outState.putBoolean("exportNames", pendingExportNames)
        outState.putBoolean("markNearOnStart", markNearOnStart)
        pendingExportId?.let { outState.putString("exportId", it) }
        resultId?.let { outState.putString("resultId", it) }
        outState.putInt("guideOrientation", createdOrientation)
        rf.activeId?.let { outState.putString("guideSession", it) }
        outState.putBoolean("guideBluetoothOff", guide.bluetoothOff)
        outState.putBoolean("guideBluetoothBack", guide.bluetoothBack)
        outState.putBoolean("guideSessionContinued", guide.sessionContinued)
        outState.putBoolean("showAllRfLab", showAllRfLab)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        watchGuideBluetooth()
    }

    override fun onResume() {
        super.onResume()
        // A session that ended while the screen was paused still gets its result card.
        val running = rf.running
        if ((wasRunning || stopRequested) && !running) { stopRequested = false; resultId = rf.lastEndedId; result = null; lastSessions = null }
        wasRunning = running
        observeGuideSession()
        refreshGuideHistory()
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
        guideBluetooth.stop()
        // Rotation keeps the session; leaving the screen ends it (R11), off the main thread.
        rf.screenStopped(isChangingConfigurations)
        if (!isChangingConfigurations) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        ++sessionsGeneration
        guideDialog?.dismiss()
        guideDialog = null
        super.onDestroy()
    }

    private fun syncScreenOn(running: Boolean) {
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onTick() {
        val running = rf.running
        syncScreenOn(running)
        observeGuideSession()
        guideEvent(GuideSteps.Tick(System.currentTimeMillis()))
        fillGuideSession()
        if (running) fillLive()
        if ((wasRunning || stopRequested) && !running) { stopRequested = false; resultId = rf.lastEndedId; result = null; lastSessions = null; render() }
        if (!running) RfRuntime.liveTable = null
        wasRunning = running
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun guideEvent(event: GuideSteps.Event) {
        val before = guide
        val updated = GuideSteps.onEvent(before, event)
        val after = if (updated.current != before.current)
            updated.copy(stepStartedAtMs = System.currentTimeMillis()) else updated
        if (after == before) return
        guide = after
        if (GuideSteps.save(after) != GuideSteps.save(before)) saveGuide()
        val moved = after.current != before.current || after.subStep != before.subStep
        val backedToStart = event is GuideSteps.Back && GuideSteps.page(after).primary?.action is GuideSteps.Action.Start
        if (rf.running && (after.current != before.current || backedToStart ||
                GuideSteps.needsStop(after) || (before.current == 3 && before.subStep == 2 && after.subStep == 3)))
            stopGuideSession()
        if (after.current != before.current || after.done != before.done || after.skipped != before.skipped)
            guideList?.let { fillGuideList(it) }
        if (moved || after.retryWalk != before.retryWalk || after.walkNumber != before.walkNumber || after.answers != before.answers)
            render(scrollToTop = moved)
    }

    private fun saveGuide() {
        val editor = guidePrefs.edit().clear()
        GuideSteps.save(guide).forEach { (key, value) -> editor.putLong(key, value) }
        editor.apply()
    }

    private fun watchGuideBluetooth() {
        if (!guide.active || !visible) return
        guideBluetooth.start(main) { on ->
            observeGuideSession()
            guideEvent(GuideSteps.BluetoothChanged(on, System.currentTimeMillis()))
        }
    }

    /** Read the existing snapshot and Location switch only. Never starts a session or a radio. */
    private fun observeGuideSession() {
        if (!guide.active) return
        val location = try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).isLocationEnabled
        } catch (_: Exception) { null }
        val snap = rf.snapshot()?.takeIf { it.running && it.provenance == Provenance.LIVE && !it.imported }
        if (snap == null || snap.id != guideSessionId) {
            val hadSession = guideSessionId != null
            guideSessionId = null
            // Also recover persisted progress after process death when no session is alive.
            if (hadSession || snap == null) guideEvent(GuideSteps.SessionEnded)
        }
        // A Location change belongs to the old session only if it is still running.
        if (location != null) guideEvent(GuideSteps.LocationChanged(location))
        if (snap != null) {
            if (guideSessionId != snap.id) {
                guideSessionId = snap.id
                // Local session ids start with their creation time, including when rotation keeps one alive.
                val startMs = snap.id.substringBefore('_').toLongOrNull() ?: Long.MIN_VALUE
                guideEvent(GuideSteps.SessionStarted(snap.scope, startMs))
                refreshGuideHistory()
            }
            guideEvent(GuideSteps.SessionContinued)
            if (snap.streams.any { it.kind == Kinds.RING_LINK && it.status == Status.NOT_CONNECTED })
                guideEvent(GuideSteps.RingLinkNotConnected)
        }
        val dayStart = todayBounds().first
        if (guideEndedId != rf.lastEndedId || guideDay != dayStart) {
            guideEndedId = rf.lastEndedId
            refreshGuideHistory()
        }
    }

    private fun todayBounds(): Pair<Long, Long> {
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = day.timeInMillis
        day.add(Calendar.DAY_OF_MONTH, 1)
        return start to day.timeInMillis
    }

    private fun refreshGuideHistory() {
        if (!guide.active) return
        val since = guide.startedAtMs
        val (dayStart, dayEnd) = todayBounds()
        guideDay = dayStart
        val generation = ++guideHistoryGeneration
        val readStartedAfterStop = !rf.running
        val store = rf.store
        Background.io.execute {
            val history = GuideHistory.read(store, since, dayStart, dayEnd).copy(readStartedAfterStop = readStartedAfterStop)
            Background.onMain {
                if (!isDestroyed && generation == guideHistoryGeneration && since == guide.startedAtMs &&
                    GuideSteps.acceptsHistory(guide, history))
                    guideEvent(history)
            }
        }
    }

    private fun renderGuide() {
        if (!guide.active) {
            section("Phone checks").apply { button("Phone test guide") { openGuideSteps() } }
            return
        }
        val page = GuideSteps.page(guide)
        val title = if (guide.finished) "All 11 steps finished"
            else "Step ${guide.current + 1} of 11: ${GuideSteps.steps[guide.current].title}"
        section("Phone test guide", title, fill = Palette.tint).apply {
            if (guide.current in 4..5) addView(label("Walk ${guide.walkNumber} of 3", 13f))
            addView(label(page.instruction, 20f, ink).apply { setPadding(0, dp(14), 0, dp(14)) })
            if ((guide.current == 3 && guide.subStep in 1..2) || (guide.current == 6 && guide.subStep == 2)) {
                guideLiveStatus = label("", 16f, ink).also { addView(it) }
                fillGuideLiveStatus()
            }
            if (guide.countdownEndMs > 0) {
                guideSessionStatus = label("", 48f, ink, bold = true).also { addView(it) }
                addView(label("seconds", 16f))
                fillGuideSession()
            }
            if (!guide.finished) {
                val primary = page.primary
                addView(makeButton(primary?.label ?: "Please wait", primary = true) {
                    primary?.let { guideTap(it.action) }
                }.apply {
                    isEnabled = primary != null && !stopRequested &&
                        (primary.action !is GuideSteps.Action.Start || !rf.running)
                    alpha = if (isEnabled) 1f else 0.45f
                }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })
                page.secondary?.let { secondary ->
                    button(secondary.label) { guideTap(secondary.action) }
                }
            }
            if (guide.current >= 10) {
                GuideSteps.answerSummary(guide).forEach { addView(label(it, 14f).apply { setPadding(0, dp(8), 0, 0) }) }
            }
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (guide.current > 0 || guide.subStep > 0) addView(guideLink("Back") { guideEvent(GuideSteps.Back(System.currentTimeMillis())) })
            addView(guideLink("All steps") { openGuideSteps() })
            if (guide.current == 9) addView(guideLink("Later") { guideTap(GuideSteps.Action.Later) })
        })
    }

    private fun guideLink(text: String, action: () -> Unit) = label(text, 14f, Palette.tintInk).apply {
        setPadding(dp(12), dp(16), dp(12), dp(16))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun guideTap(action: GuideSteps.Action) {
        when (action) {
            is GuideSteps.Action.Start -> if (!rf.running && !stopRequested) {
                scope = action.scope
                durationS = action.seconds
                startLive(markNear = guide.current in 4..5)
            }
            GuideSteps.Action.Moving, GuideSteps.Action.Far -> {
                val marker = if (action == GuideSteps.Action.Moving) "moving" else "far"
                if (rf.marker(marker)) guideEvent(GuideSteps.Tap(action, System.currentTimeMillis()))
                else observeGuideSession()
            }
            GuideSteps.Action.AppSettings -> {
                if (openGuideScreen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))))
                    guideEvent(GuideSteps.Tap(action, System.currentTimeMillis()))
            }
            GuideSteps.Action.LocationSettings -> openGuideScreen(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            GuideSteps.Action.PrivacyDashboard -> {
                if (openGuideScreen(Intent("android.intent.action.REVIEW_PERMISSION_USAGE"),
                        Intent(Settings.ACTION_PRIVACY_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
                    guideEvent(GuideSteps.Tap(action, System.currentTimeMillis()))
            }
            GuideSteps.Action.MainScreen -> {
                // Keep this screen on the back stack so returning resumes the same question.
                if (openGuideScreen(Intent(this, MainActivity::class.java)))
                    guideEvent(GuideSteps.Tap(action, System.currentTimeMillis()))
            }
            GuideSteps.Action.Later -> if (guide.current == 9) {
                saveGuide()
                Toast.makeText(this, "Saved. Come back tonight.", Toast.LENGTH_SHORT).show()
                finish()
            }
            else -> guideEvent(GuideSteps.Tap(action, System.currentTimeMillis()))
        }
    }

    private fun stopGuideSession() {
        if (stopRequested || !rf.running) return
        stopRequested = true
        Thread({ rf.stop() }, "rf-stop").start()
    }

    private fun fillGuideSession() {
        guideSessionStatus?.text = GuideSteps.secondsLeft(guide, System.currentTimeMillis()).toString()
        fillGuideLiveStatus()
    }

    private fun fillGuideLiveStatus() {
        val view = guideLiveStatus ?: return
        val snap = rf.snapshot()?.takeIf { it.running && it.provenance == Provenance.LIVE && !it.imported }
        view.text = if (guide.current == 3) {
            val location = if (!guide.locationKnown) "Location status is unavailable."
                else if (guide.locationOn) "Location is on." else "Location is off."
            val scan = snap?.streams?.firstOrNull { it.kind == Kinds.BLE_SCAN }
            location + "\n" + if (scan != null) "Bluetooth scan: ${statusText(scan.status)}"
                else if (snap == null) "Bluetooth scan: No session running." else "Bluetooth scan: Status unavailable."
        } else {
            val ring = snap?.streams?.firstOrNull { it.kind == Kinds.RING_LINK }
            "Ring link: " + if (ring != null) statusText(ring.status)
                else if (snap == null) "No session running." else "Status unavailable."
        }
    }

    private fun openGuideScreen(vararg intents: Intent): Boolean {
        for (target in intents) try {
            startActivity(target)
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next screen when this phone does not provide the requested one.
        } catch (_: SecurityException) {
            // A restricted screen may still have an available fallback.
        }
        toast("This screen could not be opened on this phone.")
        return false
    }

    private fun openGuideSteps() {
        if (!guide.active) {
            if (Build.VERSION.SDK_INT < 31) {
                AlertDialog.Builder(this).setTitle("Phone test guide")
                    .setMessage("The phone test guide needs Android 12 or newer, because My devices scanning needs it.")
                    .setPositiveButton("OK", null).show()
                return
            }
            guide = GuideSteps.start(System.currentTimeMillis())
            if (rf.running) stopGuideSession()
            saveGuide()
            watchGuideBluetooth()
            observeGuideSession()
            refreshGuideHistory()
            render(scrollToTop = true)
            return
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        fillGuideList(list)
        guideList = list
        guideDialog = AlertDialog.Builder(this).setTitle("Phone test guide")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Back to RF Lab", null)
            .setNeutralButton("Reset") { _, _ ->
                guideDialog = AlertDialog.Builder(this).setTitle("Reset the phone test guide?")
                    .setMessage("Start again at step 1. Your sessions and battery history stay saved.")
                    .setPositiveButton("Reset") { _, _ ->
                        guide = GuideSteps.start(System.currentTimeMillis())
                        guideSessionId = null
                        if (rf.running) stopGuideSession()
                        saveGuide(); observeGuideSession(); refreshGuideHistory(); render(); openGuideSteps()
                    }.setNegativeButton("Cancel", null).show()
            }.create().apply {
                setOnDismissListener { if (guideList === list) guideList = null }
                show()
            }
    }

    private fun fillGuideList(list: LinearLayout) {
        list.removeAllViews()
        GuideSteps.steps.forEachIndexed { i, step ->
            val status = when {
                guide.isSkipped(i) -> "Skipped: "
                guide.isDone(i) -> "✓ "
                i == guide.current -> "Current: "
                else -> ""
            }
            list.addView(label("$status${i + 1}. ${step.title}", 16f, ink, bold = true).apply {
                setPadding(dp(8), dp(14), dp(8), dp(6))
                if (i == guide.current) setBackgroundColor(Palette.tint)
            })
            list.addView(label(step.instructions, 13f))
        }
    }

    private fun render(scrollToTop: Boolean = false) {
        ++sessionsGeneration
        val oldScroll = if (scrollToTop) 0 else scroll.scrollY
        body.removeAllViews()
        liveBox = null
        deviceBox = null
        guideSessionStatus = null
        guideLiveStatus = null
        renderGuide()
        if (!guide.active || showAllRfLab) {
            section("RF Lab", "Signal strength in dBm", "Watch your own links for up to 10 minutes. " +
                "Sessions stay on this phone in their own files; the battery history and backups do not change.")
            if (rf.running) renderLive() else renderSetup()
            renderResult()
            renderSessions(oldScroll)
        }
        if (guide.active) body.addView(guideLink(if (showAllRfLab) "Show only the guide" else "Show all of RF Lab") {
            showAllRfLab = !showAllRfLab
            render()
        })
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
            addView(selectable("My links", selected = scope == Scope.MY_LINKS) { scope = Scope.MY_LINKS; render() })
            addView(label("Your Wi-Fi link and your Ring's link signal. No scanning.", 12.5f))
            val canScan = Build.VERSION.SDK_INT >= 31
            addView(selectable(if (canScan) "My devices" else "My devices: needs Android 12 or later",
                selected = scope == Scope.MY_DEVICES, enabled = canScan) {
                if (canScan) { scope = Scope.MY_DEVICES; render() } else toast("Scanning needs Android 12 or later")
            })
            addView(label("Adds a Bluetooth scan. Rows are kept for your Ring only; for every other device, " +
                "just a count and the strongest signal each second.", 12.5f))
            addView(selectable(if (canScan) "Everything nearby" else "Everything nearby: needs Android 12 or later",
                selected = scope == Scope.EVERYTHING_NEARBY, enabled = canScan) {
                if (canScan) { scope = Scope.EVERYTHING_NEARBY; render() } else toast("Scanning needs Android 12 or later")
            })
            addView(label("Every Bluetooth device and Wi-Fi network in range, each with a number. " +
                "Other people's devices are included.", 12.5f))
            if (scope == Scope.EVERYTHING_NEARBY) {
                addView(selectable(if (storeNames) "Store names: on" else "Store names: off", selected = storeNames) {
                    storeNames = !storeNames; render()
                })
                addView(label(if (storeNames) "Device and network names are saved with this session. " +
                    "Exports leave them out unless you tick the box." else "Devices are saved by number only.", 12.5f))
            }
            addView(label("Length", 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, 0) })
            addView(chipRow(*durations.map { (s, text) ->
                selectable(text, selected = s == durationS) { durationS = s; render() }
            }.toTypedArray()))
            button("Start", primary = true) { startLive() }
            button("Start a simulated session") { startSimulated() }
        }
        renderRadios()
    }

    /** R31: what this phone's radios support. Feature checks only. */
    private fun renderRadios() {
        section("This phone's radios", null, "What Android reports this phone supports. Nothing is scanned to find out.").apply {
            for ((name, yes) in RadioCapabilities.list(this@RfLabActivity))
                addView(label("$name: ${when (yes) { true -> "yes"; false -> "no"; null -> "unknown" }}", 12.5f))
        }
    }

    private fun started(r: StartResult) {
        // Starting can remove the oldest sessions (retention), so the cached list is out of date.
        if (r is StartResult.Started) { stopRequested = false; lastSessions = null }
        if (r is StartResult.Refused) toast(r.reason)
        else { resultId = null; result = null }
        wasRunning = rf.running
        observeGuideSession()
        render(scrollToTop = guide.active && r is StartResult.Started)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /**
     * R27: the Ring link needs Nearby devices (connect), which Battery Lab already uses for Ring
     * reads. The first use of a scanning scope explains why, then asks for scan and Precise location
     * together. The session starts whatever the answer; a stream without its permission says so.
     */
    private fun startLive(markNear: Boolean = false) {
        markNearOnStart = markNear
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (scope == Scope.EVERYTHING_NEARBY && !prefs.getBoolean(NEARBY_OK, false)) {
            AlertDialog.Builder(this).setTitle("Everything nearby")
                .setMessage(NEARBY_TEXT)
                .setPositiveButton("Continue") { _, _ -> prefs.edit().putBoolean(NEARBY_OK, true).apply(); startLive(markNear) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && PollScheduler.ringAddress(this) != null &&
            !granted(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
        val scanMissing = scope != Scope.MY_LINKS && Build.VERSION.SDK_INT >= 31 &&
            SCAN_PERMISSIONS.any { !granted(it) }
        if (scanMissing) needed += SCAN_PERMISSIONS
        when {
            needed.isEmpty() -> beginLive()
            scanMissing -> AlertDialog.Builder(this).setTitle("Scanning")
                .setMessage(SCAN_TEXT)
                .setPositiveButton("Continue") { _, _ -> requestPermissions(needed.toTypedArray(), RF_PERMISSIONS) }
                .setNegativeButton("Cancel", null)
                .show()
            else -> requestPermissions(needed.toTypedArray(), RF_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RF_PERMISSIONS && !rf.running) beginLive()
    }

    /** True (with a note) while a delete is still running; a session started now could be removed by it. */
    private fun deleteRunning(): Boolean = (RfRuntime.deletesInFlight.get() > 0).also {
        if (it) toast("Still deleting sessions. Try again in a moment.")
    }

    private fun beginLive() {
        if (deleteRunning()) return
        val markNear = markNearOnStart
        markNearOnStart = false
        val app = applicationContext
        val chosen = scope
        val ring = PollScheduler.ringAddress(app)
        val table = if (chosen == Scope.EVERYTHING_NEARBY) EmitterTable(rf.store.installKey, storeNames) else null
        val r = rf.start(StartRequest(chosen, Provenance.LIVE, durationS, emitters = table,
            streams = { sch -> liveStreams(app, chosen, ring, table, sch) }))
        if (r is StartResult.Started && table != null) RfRuntime.liveTable = r.id to table
        if (r is StartResult.Started && markNear) rf.marker("near")
        started(r)
    }

    /**
     * The streams for a scope. The Ring's address stays in memory, for connecting and matching only.
     * Pinned devices are matched by key; "Everything nearby" numbers every device in [table].
     */
    private fun liveStreams(app: Context, chosen: Scope, ring: String?, table: EmitterTable?, sch: Scheduler): List<Stream> {
        val list = mutableListOf<Stream>(WifiLinkStream(app))
        list += if (ring == null) IdleStream("ring_link", Kinds.RING_LINK, Status.NO_TARGET)
            else RingLinkStream(RingLinkAdapter(app, ring), sch)
        if (chosen != Scope.MY_LINKS) {
            val store = rf.store
            val hub = BleScanHub(BleScanAdapter(app), sch, PinMatcher(store.installKey, store.pins.all(), ring), emitters = table)
            list += hub.scanStream
            list += hub.othersStream
        }
        if (chosen == Scope.EVERYTHING_NEARBY && table != null) list += WifiScanStream(WifiScanAdapter(app), sch, table)
        return list
    }

    companion object {
        const val EXTRA_FROM_MAIN_SCREEN = "rf_from_main_screen"
        private const val PREFS = "rf_lab"
        private const val NEARBY_OK = "nearby_confirmed"

        const val NEARBY_TEXT = "Everything nearby records every Bluetooth device and Wi-Fi network your phone can " +
            "hear, including other people's, for the length of the session. Each gets a number. Their names are " +
            "saved too unless you switch Store names off, and exports leave names out unless you tick the box. " +
            "Nearby devices are never connected to or paired. If your Ring is set, its Ring link still connects " +
            "to your own Ring, as in My links. Nothing is recorded after the session ends."

        private val SCAN_PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        const val DEVICES_HINT = "Tap a Bluetooth device to pin it as yours. Pinned devices get their own rows " +
            "in My devices sessions."
        const val PIN_TEXT = "Give it a short name. Pinning only remembers this device on this phone; it never " +
            "connects to or pairs with it. Delete all sessions removes every pin."

        /** R27, word for word. */
        const val SCAN_TEXT = "Scanning lists the Bluetooth and Wi-Fi devices near you. Android only gives apps " +
            "these results when Precise location is allowed, and it can withhold them while Location is off. " +
            "Battery Lab does not read your GPS position and has no internet access. Scan data stays on this phone."
    }

    private fun startSimulated() {
        if (deleteRunning()) return
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
                if (req == null) toast("This session could not be read") else if (!deleteRunning()) started(rf.start(req))
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
            button("Stop", primary = true) { Thread({ rf.stop() }, "rf-stop").start() }
        }
        if (snap.scope == Scope.EVERYTHING_NEARBY) section("Devices", null, DEVICES_HINT).apply {
            deviceBox = LinearLayout(this@RfLabActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(deviceBox)
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
        fillGuideSession()
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
        val devices = deviceBox ?: return
        val live = RfRuntime.liveTable?.takeIf { it.first == rf.activeId }?.second ?: return
        devices.removeAllViews()
        fillDevices(devices, live.devices(), live = true, sessionId = rf.activeId)
    }

    // Device list (R31)

    private fun deviceTitle(d: DeviceView): String {
        val code = if (d.kind == EmitterTable.WIFI) "Wi-Fi network ${d.number}" else "Bluetooth device ${d.number}"
        return when {
            d.alias == PinStore.RING -> "Your Ring"
            d.alias != null -> "${d.alias} (pinned)"
            d.name != null -> "${d.name} (${d.number})"
            else -> code
        }
    }

    private fun fillDevices(box: LinearLayout, all: List<DeviceView>, live: Boolean, sessionId: String?) {
        val bt = all.count { it.kind == EmitterTable.BLE }
        box.addView(label("$bt Bluetooth devices · ${all.size - bt} Wi-Fi networks", 13f))
        box.addView(chipRow(
            selectable("Strongest first", selected = strongestFirst) { strongestFirst = true; refreshDevices() },
            selectable("First seen", selected = !strongestFirst) { strongestFirst = false; refreshDevices() },
        ))
        val sorted = if (strongestFirst) all.sortedWith(compareByDescending<DeviceView> { it.bestDbm ?: Int.MIN_VALUE }.thenBy { it.number })
            else all.sortedBy { it.number }
        val limit = if (live) 30 else if (showAllDevices) 500 else 50
        for (d in sorted.take(limit)) {
            val where = if (d.kind == EmitterTable.WIFI) "Wi-Fi" + (d.band?.let { " $it GHz" } ?: "") else "Bluetooth"
            val signal = d.bestDbm?.let { "strongest $it dBm" } ?: "no signal value"
            box.addView(makeButton("${deviceTitle(d)} · $where · $signal · ${d.rows} rows") { deviceMenu(d, sessionId) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        if (sorted.size > limit) {
            if (!live && !showAllDevices) box.addView(makeButton("Show more devices") { showAllDevices = true; render() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            else box.addView(label("${sorted.size - limit} more not shown", 12f))
        }
    }

    private fun refreshDevices() { if (rf.running) fillLive() else render() }

    /** R31: pin a Bluetooth device as yours. A pin stores a key and an alias; it never connects or pairs. */
    private fun deviceMenu(d: DeviceView, sessionId: String?) {
        val pins = rf.store.pins
        if (rf.store.keysStale(sessionId)) {
            toast("Delete all ran during this session, so its devices cannot be pinned. Pin from a new session.")
            return
        }
        val pinnable = d.kind == EmitterTable.BLE && d.key.matches(Regex("[0-9a-f]{16}")) && d.alias != PinStore.RING
        if (!pinnable) {
            toast(if (d.kind == EmitterTable.WIFI) "Wi-Fi networks cannot be pinned" else "This device cannot be pinned here")
            return
        }
        val current = pins.aliasOf(d.key)
        if (current != null) {
            AlertDialog.Builder(this).setTitle(deviceTitle(d)).setMessage("Pinned as \"$current\".")
                .setPositiveButton("Unpin") { _, _ -> pins.unpin(d.key); toast("Unpinned"); refreshDevices() }
                .setNegativeButton("Close", null).show()
            return
        }
        val edit = EditText(this).apply { hint = "Short name, like keys or earbuds"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Pin as my device").setMessage(PIN_TEXT).setView(edit)
            .setPositiveButton("Pin") { _, _ ->
                val alias = edit.text.toString().trim().lowercase(Locale.US).replace(' ', '_')
                if (pins.pin(d.key, alias)) { toast("Pinned as \"$alias\""); refreshDevices() }
                else toast("Not pinned. Use 1 to 24 letters, digits or _, not ring, and not a name already in use.")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun streamName(id: String, kind: String) = when (kind) {
        Kinds.WIFI_LINK -> "Wi-Fi link"
        Kinds.RING_LINK -> "Ring link"
        Kinds.BLE_SCAN -> "Ring broadcasts (Bluetooth scan)"
        Kinds.BLE_OTHERS -> "Other devices (strongest signal each second)"
        Kinds.WIFI_SCAN -> "Wi-Fi scan (all networks together)"
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
        Status.UNSUPPORTED -> "Not supported on this Android version"
        Status.NO_TARGET -> "Choose your Ring in Battery Lab first"
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
                    val devices = try { EmitterFile.read(dir) } catch (_: Exception) { emptyList() }
                    Background.onMain {
                        loadingResult = null
                        if (isDestroyed || resultId != id) return@onMain
                        if (computed == null) { resultId = null; toast("That session could not be read") }
                        else { result = computed; resultDevices = devices; showAllDevices = false }
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
                    "G0 as a whole needs three sessions.", 12.5f))
            }
        }
        if (resultDevices.isNotEmpty()) section("Devices", null, DEVICES_HINT).apply {
            val box = LinearLayout(this@RfLabActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(box)
            fillDevices(box, resultDevices, live = false, sessionId = resultId)
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

    private fun renderSessions(oldScroll: Int) {
        val generation = sessionsGeneration
        val showAll = showAllSessions
        val position = body.childCount
        // Show the last list right away so a tap elsewhere on the screen doesn't make the card blink;
        // the fresh list replaces it when the background read finishes.
        val shown = lastSessions?.takeIf { it.second == showAll }?.let { showSessions(it.first, showAll) }
        Background.io.execute {
            if (isDestroyed || isFinishing || generation != sessionsGeneration) return@execute
            val snapshot = try { SavedSessions.load(rf.store, showAll) } catch (e: Exception) {
                android.util.Log.w(TAG, "Saved session load failed", e); null
            }
            Background.onMain {
                if (isDestroyed || isFinishing || generation != sessionsGeneration) return@onMain
                lastSessions = snapshot?.let { it to showAll }
                val at = shown?.let { body.indexOfChild(it) }?.takeIf { it >= 0 } ?: position
                shown?.let { body.removeView(it) }
                val card = if (snapshot == null) section("Saved sessions", "Couldn't read the saved sessions. Try again in a moment.")
                    else showSessions(snapshot, showAll)
                // section() appends to body; keep the card ahead of the guide link, as before.
                body.removeView(card)
                body.addView(card, at.coerceAtMost(body.childCount))
                scroll.post {
                    if (!isDestroyed && !isFinishing && generation == sessionsGeneration) scroll.scrollTo(0, oldScroll)
                }
            }
        }
    }

    private fun showSessions(snapshot: SavedSessions.Snapshot, showAll: Boolean): LinearLayout =
        section("Saved sessions", if (snapshot.count == 0) "None yet" else "${snapshot.count} on this phone",
            "Up to ${com.batterylab.rf.SessionStore.MAX_SESSIONS} sessions or 200 MB; the oldest go first.").apply {
            for (row in snapshot.rows) {
                addView(makeButton("${row.startMs?.let { date(it) } ?: row.id} · ${row.provenance} · ${row.scope} · ${row.length}${row.imported} · ${kb(row.bytes)}") {
                    sessionMenu(row.id)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            if (snapshot.count > 20 && !showAll) button("Show ${snapshot.count - 20} older sessions") { showAllSessions = true; render() }
            button("Import a session (.zip)") { pickImport() }
            if (snapshot.count > 0) button("Delete all sessions") { confirmDeleteAll() }
        }

    private fun deleteSession(id: String) {
        // Read the running session here, on the main thread where sessions start, and hold new
        // starts until the delete is done, so the delete can't remove a session started meanwhile.
        val activeId = rf.activeId
        lastSessions = null
        RfRuntime.deletesInFlight.incrementAndGet()
        Background.io.execute {
            val deleted = try { rf.store.delete(id, activeId) } catch (e: Exception) {
                android.util.Log.w(TAG, "Session delete failed", e); null
            } finally { RfRuntime.deletesInFlight.decrementAndGet() }
            Background.onMain {
                if (isDestroyed || isFinishing) return@onMain
                // A list read queued before the tap may have refilled the cache meanwhile.
                lastSessions = null
                when (deleted) {
                    true -> {
                        if (resultId == id) resultId = null
                        refreshGuideHistory()
                        render()
                    }
                    false -> toast("A running session cannot be deleted")
                    null -> { toast("Couldn't delete the session. Try again in a moment."); render() }
                }
            }
        }
    }

    private fun sessionMenu(id: String) {
        AlertDialog.Builder(this).setItems(arrayOf("Show result", "Replay", "Export (.zip)", "Delete")) { _, which ->
            when (which) {
                0 -> { resultId = id; result = null; render() }
                1 -> startReplay(id)
                2 -> chooseExport(id)
                3 -> deleteSession(id)
            }
        }.show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this).setTitle("Delete all RF Lab sessions?")
            .setMessage("This removes every saved session and every pinned device on this phone, and device numbers " +
                "start fresh. Your battery history is not touched.")
            .setPositiveButton("Delete all") { _, _ ->
                // Same as deleteSession: the running session is read here and new starts wait.
                val activeId = rf.activeId
                lastSessions = null
                RfRuntime.deletesInFlight.incrementAndGet()
                Background.io.execute {
                    val deleted = try { rf.store.deleteAll(activeId); true } catch (e: Exception) {
                        android.util.Log.w(TAG, "Delete all sessions failed", e); false
                    } finally { RfRuntime.deletesInFlight.decrementAndGet() }
                    Background.onMain {
                        if (isDestroyed || isFinishing) return@onMain
                        lastSessions = null
                        if (deleted) resultId = null
                        else toast("Couldn't delete every session. Try again in a moment.")
                        // Even after a partial failure, show what is left on the phone.
                        refreshGuideHistory()
                        render()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** R17: names leave the phone only when the box is ticked. Off by default. */
    private fun chooseExport(id: String) {
        val mode = try { Props.parse(File(rf.store.dir(id), FileNames.PROPERTIES).readText())[Keys.IDENTIFIER_MODE] }
            catch (_: Exception) { null }
        if (mode != Keys.MODE_NAMED) { pickExport(id, false); return }
        val box = CheckBox(this).apply { text = "Include device names"; isChecked = false }
        AlertDialog.Builder(this).setTitle("Export").setMessage("This session holds the names of nearby devices and " +
            "networks, including other people's. The export leaves them out unless you tick the box.")
            .setView(box)
            .setPositiveButton("Export") { _, _ -> pickExport(id, box.isChecked) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickExport(id: String, includeNames: Boolean) {
        pendingExportNames = includeNames
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
            val names = pendingExportNames
            pendingExportNames = false
            val dir = rf.store.dir(id)
            Background.io.execute {
                val ok = try {
                    resolver.openOutputStream(uri, "wt")?.use { SessionArchive.export(dir, it, names) } != null
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
                    // An import can remove the oldest sessions (retention).
                    lastSessions = null
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
