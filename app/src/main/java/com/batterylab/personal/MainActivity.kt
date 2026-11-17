package com.batterylab.personal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import java.util.Locale

class MainActivity : StyledActivity() {
    private lateinit var store: BatteryStore
    private var reader: RingReader? = null
    private var labOpen = false
    private val HISTORY_WEEKS = 26
    private val REQUEST_BLUETOOTH = 108
    private val EXPORT_CSV = 109
    private val BIND_WIDGET = 110
    private val RESTORE_CSV = 113
    private val CONFIGURE_WIDGET = 111
    private val PICK_BACKUP = 112
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var widgetSnapshot: WidgetSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BatteryStore.get(this)
        pendingWidgetId = savedInstanceState?.getInt("pendingWidgetId", pendingWidgetId) ?: pendingWidgetId
        PhoneBattery.read(this, "app")?.let(store::append)
        PollScheduler.schedule(this)

        setUpPage()
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
        }
        // No render here: onResume always follows and renders with a fresh widget read. Rendering
        // in both built the whole page twice on every open (~280 + ~115 ms on the main thread).
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized && pageReady) {
            refreshWidget()
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("pendingWidgetId", pendingWidgetId)
    }

    /** Reads Samsung's widget (no radio use) and records labeled Ring/Watch slots. */
    private fun refreshWidget() {
        val started = android.os.SystemClock.elapsedRealtime()
        try { refreshWidgetNow() } finally {
            android.util.Log.i("BatteryLabMain", "Widget refresh (main): ${android.os.SystemClock.elapsedRealtime() - started} ms")
        }
    }

    private fun refreshWidgetNow() {
        widgetSnapshot = try {
            SamsungWidgetSource.snapshot(this)?.also { SamsungWidgetSource.record(this, store, it, "app") }
        } catch (e: Exception) {
            store.logProbe(SamsungWidgetSource.SOURCE, "Widget read failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Main-thread time per part of [render], logged under BatteryLabMain (audit #4 A6). */
    private val renderMarks = mutableListOf<Pair<String, Long>>()
    private var renderMarkAt = 0L
    private fun mark(name: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        renderMarks += name to (now - renderMarkAt); renderMarkAt = now
    }
    private fun logRender() {
        android.util.Log.i("BatteryLabMain", "Render (main): total ${renderMarks.sumOf { it.second }} ms · " +
            renderMarks.joinToString { "${it.first} ${it.second}" })
    }

    private fun render() {
        renderMarks.clear(); renderMarkAt = android.os.SystemClock.elapsedRealtime()
        val oldScroll = scroll.scrollY
        body.removeAllViews()
        val exempt = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        renderHeader(exempt)

        val ringAddress = PollScheduler.ringAddress(this)
        val ringId = PollScheduler.ringDeviceId(this)
        val watchId = store.deviceIds().filter { it.startsWith("watch:") }
            .maxByOrNull { store.latest(it)?.timeMs ?: 0L }
        val devices = listOf("phone" to "Phone", (watchId ?: "watch:unknown") to "Galaxy Watch8",
            ringId to "Galaxy Ring")
        if (!exempt) section("Heads up", "Background checks may pause for hours",
            "Android limits apps it thinks you don't use. Set Battery Lab's battery usage to Unrestricted " +
                "(App info → Battery → Unrestricted) so it keeps checking every ${PollScheduler.intervalLabel(PollScheduler.intervalMinutes(this))}.", fill = Palette.amberSoft).apply {
            button("Open Battery Lab app info", primary = true) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        if (SamsungWidgetSource.widgetId(this) == null) section("Get started", "Follow your Ring and Watch",
            "Battery Lab reads the numbers Samsung's Battery widget already shows. No Bluetooth, no extra wakeups.").apply {
            button("Connect Samsung's Battery widget", primary = true) { connectWidget() }
        }

        val colors = listOf(Palette.phone, Palette.watch, Palette.ring)
        section("Right now", "How your batteries are doing.", tagView = intervalPill(exempt)).apply {
            devices.forEachIndexed { i, (id, _) ->
                if (i > 0) addView(hairline(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(4) })
                addView(deviceBoard(id, devices[i].second, colors[i]))
            }
        }

        mark("header+right now")
        section("Last 48 hours", "How the last two days went.").apply {
            addView(chipRow(chip("Phone", colors[0]), chip("Watch", colors[1]), chip("Ring", colors[2])))
            val series = devices.map { (id, _) -> store.since(id, System.currentTimeMillis() - 48 * 3_600_000L) }
            val units = HonestyAuditor.currentUnitsPerMa(store.since("phone", System.currentTimeMillis() - 7 * 86_400_000L),
                PollScheduler.gapLimits(this@MainActivity))
            val graph = BatteryGraph(this@MainActivity, series, colors, devices.map { it.second }, units)
            val readout = label("Drag across the graph to see each reading.", 11.5f, Palette.quiet)
            graph.onScrub = { text -> readout.text = text; readout.setTextColor(ink) }
            val metricChips = mutableListOf<TextView>()
            fun paintChips() = metricChips.forEach { c ->
                val on = c.tag == graph.metric
                c.background = rounded(if (on) Palette.tint else Color.TRANSPARENT, 14, Palette.cardStroke)
                c.setTextColor(if (on) Palette.tintInk else Palette.quiet)
            }
            val metricRow = LinearLayout(this@MainActivity).apply { setPadding(0, dp(6), 0, dp(2)) }
            for (m in BatteryGraph.Metric.values()) {
                if (m != BatteryGraph.Metric.PERCENT && !graph.available(m)) continue
                metricRow.addView(TextView(this@MainActivity).apply {
                    text = m.label; tag = m; textSize = 12f; typeface = medium
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    setOnClickListener { graph.metric = m; paintChips() }
                }.also { metricChips += it }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
            paintChips()
            addView(android.widget.HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false; addView(metricRow)
            })
            addView(graph, LinearLayout.LayoutParams(-1, dp(190)))
            addView(readout.apply { setPadding(0, dp(4), 0, 0) })
            addView(label(if (units == null) "Current and power appear once the counter's units are verified. " +
                "Gaps mean Android delayed a check, not that a battery stopped."
                else "Gaps mean Android delayed a check, not that a battery stopped.", 11.5f, Palette.quiet))
        }

        mark("48h graph")
        renderBatteryLifeSection(devices, colors)
        mark("battery life")

        val backupUri = DriveBackup.uri(this)
        section("Backup", if (backupUri != null) "Backing up to your Drive." else "Keep a copy in Google Drive.",
            if (backupUri != null) DriveBackup.lastBackupMs(this)?.let {
                "Last saved ${BatteryStore.format(it)}. Updated about twice a day with your full history (zipped CSV)."
            } ?: "Waiting for the first save." else "Pick a file in Google Drive once; Battery Lab updates it about " +
                "twice a day with your full history. Your history otherwise lives only on this phone.",
            tag = if (backupUri != null) "On" else null).apply {
            if (backupUri == null) button("Choose a Drive file", primary = true) {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/gzip"
                    putExtra(Intent.EXTRA_TITLE, "battery-lab-history.csv.gz")
                }, PICK_BACKUP)
            } else {
                button("Back up now") {
                    toast("Backing up\u2026")
                    Background.io.execute {
                        val ok = DriveBackup.maybeBackup(this@MainActivity, store, force = true)
                        Background.onMain {
                            if (isDestroyed) return@onMain
                            toast(if (ok) "Saved to your backup file" else "Backup failed; check that the file still exists")
                            render()
                        }
                    }
                }
                button("Stop backing up") { DriveBackup.disable(this@MainActivity); render() }
            }
        }

        section("Stats", "For the curious", "Battery health, charging habits, hungriest hours, " +
            "rolling averages and some very nerdy conversions.").apply {
            button("Open stats", primary = true) { startActivity(Intent(this@MainActivity, StatsActivity::class.java)) }
        }

        section("Lab tools", "Sources, tests and your data", tag = if (labOpen) "Open" else null).apply {
            addView(label("The widget connection, Ring Bluetooth, the A/B test and CSV export.", 13f))
            button(if (labOpen) "Hide lab tools" else "Show lab tools") { labOpen = !labOpen; render() }
        }
        if (!labOpen) { mark("rest"); logRender(); scroll.post { scroll.scrollTo(0, oldScroll) }; return }

        renderWidgetSection()

        section("Ring Bluetooth", "Direct Ring reading", "Optional fallback when the widget is unavailable. " +
            "Each request wakes the Ring, so it is off by default.").apply {
            addView(label("Selected: ${ringAddress ?: "None"}", 13f, quiet))
            addView(label("Periodic Ring sampling: ${if (PollScheduler.ringEnabled(this@MainActivity)) "ON" else "OFF"}. " +
                "The default is OFF to avoid influencing your battery tests.", 13f, quiet))
            button("Choose paired Ring") { chooseRing() }
            button("Read Ring battery now") { readRing { } }
            button("Enter Ring % manually") { manualReading() }
            button(if (PollScheduler.ringEnabled(this@MainActivity)) "Stop Ring monitoring" else "Enable Ring monitoring (15 min)") {
                if (ringAddress == null) { toast("Choose your Ring first"); return@button }
                if (store.activeExperiment()?.mode == "baseline") {
                    toast("Finish the baseline test before enabling monitoring")
                    return@button
                }
                PollScheduler.setRingEnabled(this@MainActivity,
                    !PollScheduler.ringEnabled(this@MainActivity))
                render()
            }
            val event = store.lastProbeEvent(ringId)
            if (event != null) addView(label("Last BLE diagnostic: $event", 12f, quiet))
        }

        section("Experiment", "What costs the Ring battery?", "Run each arm for similar durations, ideally starting " +
            "near the same charge, and alternate the order. Only Ring monitoring polls Bluetooth; the others keep it off.").apply {
            val active = store.activeExperiment()
            if (active != null) {
                addView(label("ACTIVE: ${modeName(active.mode).uppercase(Locale.US)} · " +
                    "started ${BatteryStore.format(active.startMs)} at ${active.startPct}%", 14f, ink, true))
                addView(label("Elapsed: ${f((System.currentTimeMillis() - active.startMs) / 3_600_000.0)}h · Target 12h", 13f, quiet))
                button("Finish active test") { finishTest() }
            } else {
                button("Start 12h baseline · monitoring OFF") { startTest("baseline") }
                button("Start 12h monitored · monitoring ON") { startTest("monitoring") }
                button("Start Ring alone · watch off your wrist") { startTest("ring_alone") }
                button("Start Ring + Watch · both worn") { startTest("ring_with_watch") }
            }
            val runs = store.experiments(40).filter { it.device == ringId }
            if (runs.isEmpty()) addView(label("No completed tests yet", 13f, quiet))
            val ringReadings = if (runs.isEmpty()) emptyList() else store.since(ringId, runs.minOf { it.startMs })
            val results = runs.map { e ->
                ExperimentStats.result(e, ringReadings.filter { it.timeMs >= e.startMs && it.timeMs <= (e.endMs ?: Long.MAX_VALUE) })
            }
            for (r in results.take(8)) {
                val e = r.experiment
                addView(label("${modeName(e.mode)} · ${BatteryStore.format(e.startMs)} · " +
                    "${e.startPct}% → ${e.endPct?.let { "$it%" } ?: "running"} " +
                    (r.rate?.let { "(${f(it)} pp/hour)" } ?: r.quarantine?.let { "(set aside: $it)" } ?: ""), 13f, quiet))
            }
            for ((armA, armB) in listOf("baseline" to "monitoring", "ring_alone" to "ring_with_watch")) {
                val c = ExperimentStats.compare(results, armA, armB)
                if (c.meanA == null || c.meanB == null) continue
                val ci = c.paired ?: c.welch
                addView(label("${modeName(armB)} − ${modeName(armA)}: ${if (c.meanB - c.meanA > 0) "+" else ""}" +
                    "${f(c.meanB - c.meanA)} pp/hour", 15f, ink, true))
                addView(label(buildString {
                    append("${c.nA} vs ${c.nB} runs. ")
                    if (ci != null) append("95% range ${f(ci.low)} to ${f(ci.high)} (${if (c.paired != null) "paired" else "Welch"}). ")
                    c.runtimeChange?.let { append("${modeName(armB)} lasts ${if (it >= 0) "${f(it * 100)}% longer" else "${f(-it * 100)}% shorter"}. ") }
                    if (ci == null) append("Needs at least 2 runs of each before there's a range.")
                    else if (ci.low <= 0 && ci.high >= 0) append("The range includes zero, so no clear difference yet.")
                }, 12f, quiet))
            }
        }

        section("Your data", "Stays on this phone", "No account and no server. Export it any time.").apply {
            button("Export timestamped readings and tests · CSV") { export() }
            button("Restore from a backup or export (.csv / .csv.gz)") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, RESTORE_CSV)
            }
            button("Record current phone battery now") {
                PhoneBattery.read(this@MainActivity, "manual")?.let(store::append)
                render()
            }
            addView(label("For Watch8 data, install the included Wear OS companion and open it once. " +
                "Watch sampling is scheduled approximately every 30 minutes.", 12f, quiet))
        }
        mark("rest+lab tools"); logRender()
        scroll.post { scroll.scrollTo(0, oldScroll) }
    }

    private fun sourceName(source: String) = when (source) {
        SamsungWidgetSource.SOURCE -> "Samsung widget"
        "android" -> "Android"
        "wear_os" -> "Wear OS app"
        "manual_widget" -> "manual entry"
        "ring_ble" -> "Ring BLE"
        else -> source
    }

    /**
     * Per-charge runtime history and weekly trends. Ring and Watch have no health data, so the
     * weekly full-charge runtime is the longevity signal; the phone also gets capacity from
     * Android's charge counter.
     */
    private fun renderBatteryLifeSection(devices: List<Pair<String, String>>, colors: List<Int>) {
        val from = System.currentTimeMillis() - HISTORY_WEEKS * 7 * 86_400_000L
        val day = java.text.SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        val weekFmt = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
        val perDevice = devices.map { (id, _) ->
            val readings = BatteryStats.fullHoldAsCharging(store.since(id, from))
            readings to BatteryMath.dischargeStretches(readings.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = PollScheduler.gapLimits(this))
        }
        // One scale for every bar so devices compare at a glance.
        val maxHours = perDevice.flatMap { (_, s) -> s.takeLast(4).map { it.fullChargeHours } }.maxOrNull() ?: 1.0
        section("Battery life", "How long a charge lasts.", "Each bar is one stretch between charges, " +
            "projected to a full 100% → 0% at the rate it actually drained.").apply {
            devices.forEachIndexed { i, (id, name) ->
                val (readings, stretches) = perDevice[i]
                addView(deviceBanner(name, colors[i]))
                if (stretches.isEmpty()) {
                    addView(label("Not enough yet: needs an hour of use between charges.", 12.5f, Palette.quiet))
                    return@forEachIndexed
                }
                for (s in stretches.takeLast(4).asReversed()) addView(barRow(
                    day.format(s.startMs).uppercase(Locale.getDefault()), s.fullChargeHours / maxHours,
                    "${s.fullChargeHours.roundToInt()} h", colors[i],
                    "${s.fromPct}% → ${s.toPct}% over ${f1(s.hours)} h"))
                val weeks = BatteryMath.weeklyRuntime(stretches, ::weekStart).takeLast(6)
                if (weeks.size > 1) {
                    addView(eyebrow("By week").apply { setPadding(0, dp(10), 0, dp(2)) })
                    val top = weeks.maxOf { it.fullChargeHours }
                    for (w in weeks.asReversed()) addView(barRow(weekFmt.format(w.weekStartMs).uppercase(Locale.getDefault()),
                        w.fullChargeHours / top, "${w.fullChargeHours.roundToInt()} h", colors[i]))
                }
                if (id == "phone") {
                    val mah = BatteryMath.median(readings.filter { it.timeMs >= weekStart(System.currentTimeMillis()) }
                        .mapNotNull { BatteryMath.impliedCapacityMah(it.percent, it.remainingUah, it.charging) })
                    if (mah != null) addView(label("Battery capacity this week: about ${String.format(Locale.getDefault(), "%,d", mah.roundToInt())} mAh " +
                        "· estimated from Android's charge counter", 12f, Palette.quiet).apply { setPadding(0, dp(8), 0, 0) })
                }
            }
        }
    }

    private fun renderHeader(exempt: Boolean) {
        body.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(18))
            addView(TextView(this@MainActivity).apply {
                text = "b"
                textSize = 24f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(ink)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, dp(3))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.amber) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "battery lab."
                    textSize = 28f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    letterSpacing = -0.03f
                    setTextColor(ink)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Every charge, every device."
                    textSize = 12f
                    setTextColor(Palette.body)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    /** "● Every 15 min ▾" pill for the Right now card; tapping it picks how often to check. */
    private fun intervalPill(exempt: Boolean): View = TextView(this).apply {
        val minutes = PollScheduler.intervalMinutes(this@MainActivity)
        val words = if (exempt) "Every ${PollScheduler.intervalLabel(minutes)}" else "Checks limited"
        val s = android.text.SpannableString("●  $words  ▾")
        s.setSpan(android.text.style.ForegroundColorSpan(if (exempt) Palette.green else Palette.amber), 0, 1, 0)
        text = s
        textSize = 11.5f
        typeface = medium
        setTextColor(ink)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(40, 36, 69, 107)),
            rounded(Color.argb(215, 255, 253, 248), 14, Palette.cardStroke), null)
        contentDescription = "Check interval: $words. Tap to change."
        setOnClickListener { v ->
            android.widget.PopupMenu(this@MainActivity, v).apply {
                PollScheduler.INTERVAL_CHOICES.forEachIndexed { i, m ->
                    menu.add(0, m, i, "Every ${PollScheduler.intervalLabel(m)}" + if (m == 15) "  (recommended)" else "")
                        .apply { isCheckable = true; isChecked = m == minutes }
                }
                setOnMenuItemClickListener { item ->
                    if (item.itemId != minutes) {
                        PollScheduler.setIntervalMinutes(this@MainActivity, item.itemId)
                        toast("Checking every ${PollScheduler.intervalLabel(item.itemId)}")
                        render()
                    }
                    true
                }
                show()
            }
        }
    }

    /** One device: color gauge, name with a status pill, hours left on the right, a quiet meta line. */
    private fun deviceBoard(id: String, name: String, color: Int): View {
        val recent = BatteryStats.fullHoldAsCharging(store.recent(id, 1000))
        val last = recent.lastOrNull()
        val now = System.currentTimeMillis()
        // The phone's own job can sleep for hours in Doze, so it gets a long allowance. A wearable
        // that misses three checks in a row, or drops out of Samsung's widget, is treated as offline:
        // its last number is history, not a live reading.
        val staleAfter = if (id == "phone") maxOf(6 * 3_600_000L, PollScheduler.gapLimits(this).stepMs)
            else maxOf(45 * 60_000L, 3L * PollScheduler.intervalMinutes(this) * 60_000L)
        val dropped = last?.source == SamsungWidgetSource.SOURCE && id in SamsungWidgetSource.missingDevices(this)
        val fresh = last != null && !dropped && now - last.timeMs <= staleAfter
        val points = recent.map { BatteryPoint(it.timeMs, it.percent, it.charging) }
        val projection = if (fresh) BatteryMath.estimate(points, PollScheduler.gapLimits(this)) else null
        // While the current stretch is too short to time, fall back on this device's past drain.
        val historyRate = if (fresh && projection == null && last?.charging != true) BatteryMath.historicalRate(
            BatteryMath.dischargeStretches(points, gaps = PollScheduler.gapLimits(this)).filter { it.endMs < last!!.timeMs }) else null
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        val seenAt = last?.let {
            android.text.format.DateUtils.formatDateTime(this, it.timeMs, android.text.format.DateUtils.FORMAT_SHOW_TIME or
                if (android.text.format.DateUtils.isToday(it.timeMs)) 0
                else android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
        }
        // A second source's latest number, shown only when it was read near the same time and
        // disagrees. The phone's comes from Samsung's widget; a wearable's from its other source.
        val other = when {
            last == null || !fresh -> null
            id == "phone" -> store.latest(SamsungWidgetSource.PHONE_WIDGET_DEVICE)
            else -> recent.lastOrNull { it.source != last.source }
        }?.takeIf { kotlin.math.abs(it.timeMs - last!!.timeMs) <= 30 * 60_000L && it.percent != last.percent }
        val otherLabel = when (other?.source) {
            SamsungWidgetSource.SOURCE -> "Samsung widget"
            "ring_ble" -> "Ring over Bluetooth"
            "android" -> "Android"
            "wear_os" -> "Watch app"
            "manual_widget" -> "Your entry"
            else -> "Other source"
        }
        // Android's own estimates exist only for this phone; Samsung's widget offers none.
        val androidEstimate = if (id == "phone" && fresh) AndroidEstimates.read(this) else null
        fun span(ms: Long) = BatteryMath.timeLeftLabel(ms / 3_600_000.0).let { (v, u) -> "$v ${u.removeSuffix(" left")}" }
        val androidLine = when {
            androidEstimate == null -> null
            last?.charging == true -> null   // shown as the big number on the right instead
            else -> androidEstimate.dischargeMs?.let { "Android: ${span(it)} left" }
        }
        val (status, statusColor) = when {
            last == null -> "No data yet" to Palette.quiet
            !fresh -> "Offline" to Palette.amberInk
            last.charging == true -> "Charging" to Palette.green
            // Widget shows a full wearable without a bolt whether or not it is still on the charger.
            last.percent == 100 && last.source == SamsungWidgetSource.SOURCE -> "Full" to Palette.green
            else -> "On battery" to Palette.tintInk
        }
        return LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
            // An offline device gets an empty gauge; its last value moves to the line below.
            addView(GaugeView(this@MainActivity, if (fresh) last?.percent else null, color), LinearLayout.LayoutParams(dp(68), dp(68)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(6), 0)
                // Name and status pill share one line to keep each device row compact.
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = name; textSize = 17f; typeface = medium; setTextColor(ink)
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(-2, -2, 0f))
                    addView(TextView(this@MainActivity).apply {
                        text = status; textSize = 11f; typeface = medium; letterSpacing = 0.04f
                        setTextColor(statusColor); maxLines = 1
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                        background = rounded(Color.argb(28, Color.red(statusColor), Color.green(statusColor), Color.blue(statusColor)), 6)
                    }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
                addView(TextView(this@MainActivity).apply {
                    text = if (last != null && !fresh) "Last seen $seenAt at ${last.percent}%" else buildList {
                        if (last != null) add("Updated ${time.format(java.util.Date(last.timeMs))}")
                        // Read recently is not the same as changed recently (audit #4 A1).
                        val same = BatteryMath.unchangedSince(recent)
                        if (last != null && same != null && last.timeMs - same >= 60 * 60_000L && status != "Full")
                            add("same since ${time.format(java.util.Date(same))}")
                        if (projection != null) add("about ${f1(projection.percentPerHour)}% an hour")
                        else if (last != null && last.charging != true && status != "Full")
                            add(if (historyRate != null) "this stretch still calibrating" else "needs 4% drop")
                    }.joinToString(" · ").ifEmpty { "Waiting for the first reading" }
                    textSize = 11.5f; setTextColor(Palette.quiet)
                })
                if (androidLine != null) addView(TextView(this@MainActivity).apply {
                    text = androidLine
                    textSize = 11.5f; setTextColor(Palette.tintInk)
                })
                if (other != null) addView(TextView(this@MainActivity).apply {
                    text = "$otherLabel says ${other.percent}%"
                    textSize = 11.5f; typeface = medium; setTextColor(Palette.amberInk)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
                // Count down from the reading's time, not from now: an old reading means less left.
                val chargeMs = if (last?.charging == true) androidEstimate?.chargeMs else null
                val (value, unit) = when {
                    projection != null && last != null -> BatteryMath.timeLeftLabel(
                        BatteryMath.timeLeftNow(projection.hoursRemaining, last.timeMs, System.currentTimeMillis()))
                    chargeMs != null -> BatteryMath.timeLeftLabel(chargeMs / 3_600_000.0).let { (v, u) -> v to u.replace("left", "to full") }
                    last?.charging == true && last.percent == 100 -> "Full" to "on charger"
                    // No live estimate: say why instead of a bare dash.
                    last == null -> "—" to "waiting"
                    !fresh -> "—" to "stale signal"
                    last.charging == true -> "—" to "charging"
                    // A full wearable may still be in its case; don't count down yet.
                    last.percent == 100 && last.source == SamsungWidgetSource.SOURCE -> "—" to "full"
                    historyRate != null -> BatteryMath.timeLeftLabel(
                        BatteryMath.timeLeftNow(last.percent / historyRate, last.timeMs, System.currentTimeMillis()))
                        .first to "est. (history)"
                    else -> "—" to "calibrating"
                }
                addView(TextView(this@MainActivity).apply {
                    text = value
                    textSize = 28f; typeface = medium; setTextColor(ink)
                })
                addView(TextView(this@MainActivity).apply {
                    text = unit
                    textSize = 11f; setTextColor(Palette.quiet)
                })
                if (projection != null || chargeMs != null || unit == "est. (history)") addView(TextView(this@MainActivity).apply {
                    text = if (chargeMs != null && projection == null) "Android" else "Battery Lab"
                    textSize = 9.5f; setTextColor(Palette.quiet)
                })
            })
        }
    }

    private fun weekStart(timeMs: Long): Long = java.util.Calendar.getInstance().run {
        timeInMillis = timeMs
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        while (get(java.util.Calendar.DAY_OF_WEEK) != firstDayOfWeek) add(java.util.Calendar.DAY_OF_MONTH, -1)
        timeInMillis
    }

    private fun renderWidgetSection() {
        val connected = SamsungWidgetSource.widgetId(this) != null
        // The Unrestricted warning lives at the top of the page (see render): without the exemption,
        // Adaptive Battery demoted the app to RARE in the soak test and the job stopped for hours.
        section("Widget source", "Samsung Battery widget", "Reads the percentages Samsung's widget already shows, " +
            "about every 15 minutes and whenever you open Battery Lab. No Bluetooth, no extra wakeups.",
            tag = if (connected) "Connected" else "Off").apply {
            if (!connected) {
                addView(label("Not connected. Android will ask you to allow Battery Lab to use the widget.", 13f, quiet))
                button("Connect Samsung Battery widget") { connectWidget() }
                return@apply
            }
            val snap = widgetSnapshot
            if (snap == null || snap.slots.isEmpty()) {
                addView(label("Connected, but no battery values were readable yet. Tap Read now in a few seconds.", 13f, quiet))
            } else {
                val phone = PhoneBattery.read(this@MainActivity, "app")
                addView(label("Widget read ${BatteryStore.format(snap.timeMs)}. Tap a device to label it once.", 13f, quiet))
                for (slot in snap.slots) addView(slotRow(slot, phone))
                if (snap.slots.any { SamsungWidgetSource.labelFor(this@MainActivity, it.fingerprint) == null })
                    addView(label("Unlabeled devices are not recorded. Compare with your home-screen widget to label them.", 12f, quiet))
            }
            button("Read widget now") { refreshWidget(); render() }
            button("Disconnect widget source") {
                SamsungWidgetSource.disconnect(this@MainActivity)
                widgetSnapshot = null
                render()
            }
        }
    }

    private fun slotRow(slot: WidgetSlot, phone: Reading?): View {
        val labelName = when (SamsungWidgetSource.labelFor(this, slot.fingerprint)) {
            SamsungWidgetSource.LABEL_RING -> "Galaxy Ring"
            SamsungWidgetSource.LABEL_WATCH -> "Galaxy Watch"
            SamsungWidgetSource.LABEL_PHONE -> "This phone (kept for comparison)"
            SamsungWidgetSource.LABEL_OTHER -> "Other device (ignored)"
            else -> "Unlabeled — tap to label"
        }
        val hint = if (phone != null && kotlin.math.abs(phone.percent - slot.percent) <= 1)
            " · matches this phone's ${phone.percent}%" else ""
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(slot.icon)
                background = GradientDrawable().apply { setColor(Color.rgb(233, 238, 245)); cornerRadius = dp(8).toFloat() }
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
            addView(label("${slot.percent}%${if (slot.chargingIcon) " ⚡" else ""}  ·  $labelName$hint", 14f, ink))
            setOnClickListener { chooseLabel(slot) }
        }
    }

    private fun chooseLabel(slot: WidgetSlot) {
        val fingerprint = slot.fingerprint ?: run { toast("This slot has no icon to remember"); return }
        val labels = listOf(SamsungWidgetSource.LABEL_RING, SamsungWidgetSource.LABEL_WATCH,
            SamsungWidgetSource.LABEL_PHONE, SamsungWidgetSource.LABEL_OTHER)
        val names = arrayOf("Galaxy Ring", "Galaxy Watch", "This phone", "Other device / ignore")
        AlertDialog.Builder(this).setTitle("Which device shows ${slot.percent}% in Samsung's widget?")
            .setItems(names) { _, which ->
                SamsungWidgetSource.setLabel(this, fingerprint, labels[which])
                widgetSnapshot?.let { SamsungWidgetSource.record(this, store, it, "manual") }
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun connectWidget() {
        val info = SamsungWidgetSource.providerInfo(this)
            ?: run { toast("Samsung's Battery widget was not found on this phone"); return }
        val id = SamsungWidgetSource.host(this).allocateAppWidgetId()
        if (AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            widgetBound(id)
            return
        }
        pendingWidgetId = id
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), BIND_WIDGET)
    }

    private fun widgetBound(id: Int) {
        SamsungWidgetSource.onBound(this, id)
        val configure = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.configure
        if (configure != null) {
            try {
                SamsungWidgetSource.host(this).startAppWidgetConfigureActivityForResult(this, id, 0, CONFIGURE_WIDGET, null)
                return
            } catch (e: Exception) {
                store.logProbe(SamsungWidgetSource.SOURCE, "Widget settings screen unavailable: ${e.javaClass.simpleName}")
            }
        }
        afterWidgetReady()
    }

    /**
     * Samsung renders asynchronously after binding/resizing; read shortly afterwards and report
     * what the widget actually shows, not just that binding finished.
     */
    private fun afterWidgetReady(settingsCancelled: Boolean = false) {
        if (settingsCancelled) store.logProbe(SamsungWidgetSource.SOURCE, "Widget settings screen cancelled")
        toast(if (settingsCancelled) "Widget settings cancelled. Checking whether the widget still shows readings…"
              else "Checking the Samsung Battery widget…")
        render()
        body.postDelayed({
            if (isDestroyed) return@postDelayed
            refreshWidget()
            val slots = widgetSnapshot?.slots?.size ?: 0
            toast(when {
                slots > 0 -> "Samsung Battery widget connected ($slots ${if (slots == 1) "device" else "devices"} visible)"
                settingsCancelled -> "Widget added, but it shows no readings. Tap Connect again to finish its settings."
                else -> "Widget added, but it shows no readings yet. Check again in a minute."
            })
            render()
        }, 1500)
    }

    private fun chooseRing() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
            return
        }
        val devices = RingReader.pairedDevices(this)
        if (devices.isEmpty()) {
            toast("No paired Bluetooth devices visible. Pair your Ring in Galaxy Wearable first.")
            return
        }
        // Every bonded device is listed (a renamed Ring must stay choosable), but likely Rings come
        // first and the rest are marked, so a headset or car is harder to pick by mistake.
        val ringFirst = devices.sortedBy { if (RingReader.looksLikeRing(it.name)) 0 else 1 }
        val names = ringFirst.map {
            "${it.name ?: "Unnamed"}  (${it.address})" + if (RingReader.looksLikeRing(it.name)) "" else "  · not a Ring?"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose your already-paired Galaxy Ring")
            .setItems(names) { _, which ->
                if (store.activeExperiment() != null) {
                    toast("Finish the current experiment before changing Ring")
                    return@setItems
                }
                PollScheduler.setRingEnabled(this, false)
                PollScheduler.prefs(this).edit().putString(PollScheduler.RING_ADDRESS, ringFirst[which].address).apply()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun readRing(ready: (Reading) -> Unit) {
        val address = PollScheduler.ringAddress(this)
        if (address == null) { toast("Choose your paired Ring first"); return }
        if (reader != null) { toast("A Ring reading is already in progress"); return }
        toast("Requesting Ring battery percentage…")
        reader = RingReader(applicationContext, address) { reading, error ->
            reader = null
            if (reading != null) {
                store.append(reading)
                ready(reading)
                toast("Ring: ${reading.percent}%")
            } else {
                store.logProbe(BatteryStore.RING_ID, error ?: "Unknown BLE error")
                toast("Ring BLE failed: ${error ?: "Unknown"}. Manual entry is available.")
            }
            render()
        }.also { it.start() }
    }

    private fun manualReading() {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Battery percentage (0–100)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Enter Samsung widget Ring reading")
            .setView(edit).setPositiveButton("Save") { _, _ ->
                val pct = edit.text.toString().toIntOrNull()
                if (pct == null || pct !in 0..100) { toast("Enter 0–100"); return@setPositiveButton }
                store.append(Reading(PollScheduler.ringDeviceId(this), System.currentTimeMillis(), pct, null, "manual_widget",
                    detail = ReadingDetail.clock(this, "manual")))
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    /**
     * Prefers sources that do not wake the Ring: what the Samsung widget shows now, then a recent
     * manual entry, then BLE, which only the monitoring arm may use.
     */
    private fun freshRingReading(mode: String, next: (Reading) -> Unit) {
        refreshWidget()
        val widgetRing = widgetSnapshot?.slots?.firstOrNull {
            SamsungWidgetSource.labelFor(this, it.fingerprint) == SamsungWidgetSource.LABEL_RING
        }?.percent
        val choice = ExperimentEndpoint.choose(mode, widgetRing, store.latest(PollScheduler.ringDeviceId(this)),
            System.currentTimeMillis(), PollScheduler.ringAddress(this) != null)
        when (choice) {
            is ExperimentEndpoint.Choice.Use -> next(choice.reading)
            ExperimentEndpoint.Choice.ReadBle -> readRing(next)
            is ExperimentEndpoint.Choice.Refuse -> toast(choice.reason)
        }
    }

    private fun modeName(mode: String) = when (mode) {
        "baseline" -> "Baseline"; "monitoring" -> "Monitored"; "ring_alone" -> "Ring alone"; "ring_with_watch" -> "Ring + Watch"
        else -> mode
    }

    private fun startTest(mode: String) {
        if (store.activeExperiment() != null) { toast("Finish current test first"); return }
        if (mode == "monitoring" && PollScheduler.ringAddress(this) == null) {
            toast("A monitored test polls the Ring over BLE. Choose your paired Ring first.")
            return
        }
        PollScheduler.setRingEnabled(this, false)
        freshRingReading(mode) { reading ->
            store.startExperiment(reading.device, mode, reading)
            PollScheduler.setRingEnabled(this, mode == "monitoring")
            toast("${mode.replaceFirstChar { it.uppercase() }} test started at ${reading.percent}%")
            render()
        }
    }

    private fun finishTest() {
        val experiment = store.activeExperiment() ?: return
        if (PollScheduler.ringDeviceId(this) != experiment.device) {
            toast("Original Ring no longer selected")
            return
        }
        // Monitoring is switched off only once the final reading is saved; if the read fails the
        // test and its settings stay exactly as they were.
        freshRingReading(experiment.mode) { reading ->
            store.finishExperiment(reading)
            PollScheduler.setRingEnabled(this, false)
            toast("Experiment finished")
            render()
        }
    }

    private fun export() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "battery-lab-${System.currentTimeMillis()}.csv")
        }
        startActivityForResult(intent, EXPORT_CSV)
    }

    @Deprecated("Legacy result API used to avoid extra runtime dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BIND_WIDGET) {
            val id = pendingWidgetId
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
            if (resultCode == RESULT_OK) widgetBound(id)
            else {
                SamsungWidgetSource.host(this).deleteAppWidgetId(id)
                toast("Widget access was not allowed")
            }
            return
        }
        if (requestCode == CONFIGURE_WIDGET) {
            afterWidgetReady(settingsCancelled = resultCode != RESULT_OK)
            return
        }
        if (requestCode == PICK_BACKUP && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                DriveBackup.enable(this, uri)
            } catch (e: Exception) { toast("Couldn't use that file: ${e.message}"); return }
            Background.io.execute {
                val ok = DriveBackup.maybeBackup(this, store, force = true)
                Background.onMain {
                    if (isDestroyed) return@onMain
                    toast(if (ok) "Backup on: first copy saved" else "Backup on; first save failed")
                    render()
                }
            }
            render()
            return
        }
        if (requestCode == RESTORE_CSV && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            toast("Restoring\u2026")
            Background.io.execute {
                val message = try {
                    val raw = contentResolver.openInputStream(uri) ?: error("the file couldn't be opened")
                    val input = java.io.BufferedInputStream(raw).apply { mark(2) }
                    val gz = input.read() == 0x1f && input.read() == 0x8b
                    input.reset()
                    val stream = if (gz) java.util.zip.GZIPInputStream(input) else input
                    val (rows, tests) = stream.bufferedReader().use(store::importCsv)
                    "Restored $rows readings and $tests tests (duplicates skipped)"
                } catch (e: Exception) { "Restore failed: ${e.message ?: e.javaClass.simpleName}" }
                Background.onMain { if (!isDestroyed) { toast(message); render() } }
            }
            return
        }
        if (requestCode == EXPORT_CSV && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Background.io.execute {
                val error = try {
                    val out = contentResolver.openOutputStream(uri) ?: error("the app that holds the file didn't open it")
                    out.bufferedWriter().use(store::exportCsv)
                    null
                } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                Background.onMain { if (!isDestroyed) toast(if (error == null) "CSV exported" else "CSV export failed: $error") }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun f(number: Double): String = String.format(Locale.US, "%.2f", number)

    override fun onDestroy() {
        reader?.cancel()
        if (::store.isInitialized) store.close()
        super.onDestroy()
    }
}

/** Simple timestamp-proportional history visualization. No invented or interpolated readings. */
/** 48-hour line chart on a light card: hairline grid, device-colored lines, emphasized latest point. */
/**
 * The 48-hour graph. Shows one metric at a time (percent, voltage, current, power or
 * temperature) and can be scrubbed: drag a finger across it and [onScrub] gets a readout of the
 * nearest reading on each line.
 */
class BatteryGraph(context: android.content.Context, private val series: List<List<Reading>>,
                   private val colors: List<Int>, private val names: List<String> = emptyList(),
                   /** Raw current-counter units per mA, when verified; current and power need it. */
                   private val unitsPerMa: Double? = null) : View(context) {
    enum class Metric(val label: String, val unit: String) {
        PERCENT("Charge", "%"), VOLTAGE("Voltage", "mV"), CURRENT("Current", "mA"), POWER("Power", "W"), TEMP("Temp", "\u00b0C")
    }

    var metric = Metric.PERCENT
        set(value) { field = value; invalidate() }
    var onScrub: ((String?) -> Unit)? = null
    private var cursorMs: Long? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val end = System.currentTimeMillis()
    private val start = end - 48L * 3_600_000L
    private val d = resources.displayMetrics.density
    private val left get() = 34f * d
    private val right get() = width - 6f * d

    fun value(r: Reading, m: Metric = metric): Double? = when (m) {
        Metric.PERCENT -> r.percent.toDouble()
        Metric.VOLTAGE -> r.voltageMv?.toDouble()
        Metric.CURRENT -> unitsPerMa?.let { u -> r.detail?.currentNowRaw?.let { it / u } }
        Metric.POWER -> value(r, Metric.CURRENT)?.let { ma -> r.voltageMv?.let { kotlin.math.abs(ma) * it / 1_000_000.0 } }
        Metric.TEMP -> r.tempTenthsC?.let { it / 10.0 }
    }

    fun available(m: Metric) = series.any { s -> s.any { it.timeMs >= start && value(it, m) != null } }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; scrubbing = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(event.x - downX); val dy = kotlin.math.abs(event.y - downY)
                // Only a sideways drag scrubs; an up-or-down swipe is left to the page so it scrolls.
                if (!scrubbing && dx > slop && dx > dy) { scrubbing = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (scrubbing) scrubTo(event.x)
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (!scrubbing) scrubTo(event.x)    // a tap shows the readout at that spot
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            android.view.MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private fun scrubTo(px: Float) {
        val frac = ((px - left) / (right - left)).coerceIn(0f, 1f)
        val t = start + ((end - start) * frac).toLong()
        cursorMs = t
        onScrub?.invoke(readout(t))
        invalidate()
    }

    private fun nearest(data: List<Reading>, t: Long) =
        data.minByOrNull { kotlin.math.abs(it.timeMs - t) }?.takeIf { kotlin.math.abs(it.timeMs - t) <= 45 * 60_000L }

    private fun readout(t: Long): String {
        val time = java.text.SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(java.util.Date(t))
        val lines = series.mapIndexedNotNull { i, data ->
            val r = nearest(data, t) ?: return@mapIndexedNotNull null
            val parts = mutableListOf("${r.percent}%")
            r.voltageMv?.let { parts += "$it mV" }
            value(r, Metric.CURRENT)?.let { parts += "${it.roundToInt()} mA" }
            value(r, Metric.POWER)?.let { parts += String.format(Locale.US, "%.2f W", it) }
            r.tempTenthsC?.let { parts += String.format(Locale.US, "%.1f\u00b0C", it / 10.0) }
            r.detail?.transport?.let { parts += it }
            parts += r.source
            "${names.getOrElse(i) { "Line ${i + 1}" }}: ${parts.joinToString(" \u00b7 ")}"
        }
        return if (lines.isEmpty()) "$time \u00b7 no reading nearby" else "$time\n${lines.joinToString("\n")}"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val top = 8f * d
        val bottom = h - 22f * d
        val visible = series.map { s -> s.filter { it.timeMs in start..end }.mapNotNull { r -> value(r)?.let { r.timeMs to it } } }
        val all = visible.flatten().map { it.second }
        val (lo, hi) = if (metric == Metric.PERCENT || all.isEmpty()) 0.0 to 100.0 else {
            val mn = all.minOrNull()!!; val mx = all.maxOrNull()!!
            val pad = ((mx - mn) * 0.08).coerceAtLeast(if (metric == Metric.VOLTAGE) 20.0 else 0.5)
            (mn - pad) to (mx + pad)
        }
        fun x(t: Long) = left + (right - left) * (t - start).toFloat() / (end - start)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()
        fun tick(v: Double) = when (metric) {
            Metric.PERCENT -> "${v.roundToInt()}%"
            Metric.POWER -> String.format(Locale.US, "%.1f", v)
            Metric.TEMP -> String.format(Locale.US, "%.0f\u00b0", v)
            else -> "${v.roundToInt()}"
        }
        paint.textSize = 10f * d
        for (v in listOf(lo, (lo + hi) / 2, hi)) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Palette.hairline
            canvas.drawLine(left, y(v), right, y(v), paint)
            paint.style = Paint.Style.FILL; paint.color = Palette.quiet
            canvas.drawText(tick(v), 0f, y(v) + 4f * d, paint)
        }
        if (metric == Metric.CURRENT && lo < 0 && hi > 0) {
            paint.style = Paint.Style.STROKE; paint.color = Palette.quiet
            canvas.drawLine(left, y(0.0), right, y(0.0), paint)
        }
        visible.forEachIndexed { index, pts ->
            if (pts.isEmpty()) return@forEachIndexed
            paint.color = colors[index % colors.size]
            paint.strokeWidth = 2.5f * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.style = Paint.Style.STROKE
            val path = Path()
            pts.forEachIndexed { i, (t, v) ->
                if (i == 0 || t - pts[i - 1].first > 8 * 3_600_000L) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
            }
            canvas.drawPath(path, paint)
            val (lt, lv) = pts.last()
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(x(lt), y(lv), 5f * d, paint)
            paint.color = colors[index % colors.size]
            canvas.drawCircle(x(lt), y(lv), 3.5f * d, paint)
        }
        cursorMs?.let { t ->
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Palette.ink
            canvas.drawLine(x(t), top, x(t), bottom, paint)
            paint.style = Paint.Style.FILL
            series.forEachIndexed { i, data ->
                val r = nearest(data, t) ?: return@forEachIndexed
                val v = value(r) ?: return@forEachIndexed
                paint.color = colors[i % colors.size]
                canvas.drawCircle(x(r.timeMs), y(v), 4.5f * d, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = Palette.quiet
        paint.textSize = 10.5f * d
        canvas.drawText("2 days ago", left, h - 4f * d, paint)
        canvas.drawText("yesterday", (left + right) / 2 - 22f * d, h - 4f * d, paint)
        canvas.drawText("now", right - 20f * d, h - 4f * d, paint)
    }
}
