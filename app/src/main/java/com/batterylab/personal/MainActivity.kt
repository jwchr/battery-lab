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
        render()
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
        widgetSnapshot = try {
            SamsungWidgetSource.snapshot(this)?.also { SamsungWidgetSource.record(this, store, it, "app") }
        } catch (e: Exception) {
            store.logProbe(SamsungWidgetSource.SOURCE, "Widget read failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun render() {
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

        section("Last 48 hours", "How the last two days went.").apply {
            addView(chipRow(chip("Phone", colors[0]), chip("Watch", colors[1]), chip("Ring", colors[2])))
            addView(BatteryGraph(this@MainActivity, devices.map { (id, _) -> store.since(id, System.currentTimeMillis() - 48 * 3_600_000L) }, colors),
                LinearLayout.LayoutParams(-1, dp(190)))
            addView(label("Gaps mean Android delayed a check, not that a battery stopped.", 11.5f, Palette.quiet))
        }

        renderBatteryLifeSection(devices, colors)

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
        if (!labOpen) { scroll.post { scroll.scrollTo(0, oldScroll) }; return }

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

        section("Experiment", "Does Ring monitoring cost battery?", "Run baseline and monitored periods for similar " +
            "durations, ideally starting near the same charge. Repeat in alternating order.").apply {
            val active = store.activeExperiment()
            if (active != null) {
                addView(label("ACTIVE: ${active.mode.uppercase(Locale.US)} · " +
                    "started ${BatteryStore.format(active.startMs)} at ${active.startPct}%", 14f, ink, true))
                addView(label("Elapsed: ${f((System.currentTimeMillis() - active.startMs) / 3_600_000.0)}h · Target 12h", 13f, quiet))
                button("Finish active test") { finishTest() }
            } else {
                button("Start 12h baseline · monitoring OFF") { startTest("baseline") }
                button("Start 12h monitored · monitoring ON") { startTest("monitoring") }
            }
            val results = store.experiments(20).filter { it.device == ringId }
            if (results.isEmpty()) addView(label("No completed tests yet", 13f, quiet))
            for (e in results) {
                val rate = BatteryMath.experimentDrain(e.startMs, e.endMs, e.startPct, e.endPct)
                addView(label("${e.mode.replaceFirstChar { it.uppercase() }} · ${BatteryStore.format(e.startMs)} · " +
                    "${e.startPct}% → ${e.endPct?.let { "$it%" } ?: "running"} " +
                    (rate?.let { "(${f(it)} pp/hour)" } ?: ""), 13f, quiet))
            }
            val baseline = results.filter { it.mode == "baseline" }.mapNotNull {
                BatteryMath.experimentDrain(it.startMs, it.endMs, it.startPct, it.endPct)
            }
            val monitored = results.filter { it.mode == "monitoring" }.mapNotNull {
                BatteryMath.experimentDrain(it.startMs, it.endMs, it.startPct, it.endPct)
            }
            if (baseline.isNotEmpty() && monitored.isNotEmpty()) {
                val difference = monitored.average() - baseline.average()
                addView(label("Observed monitored − baseline: ${if (difference > 0) "+" else ""}${f(difference)} pp/hour", 15f, ink, true))
                addView(label("Not a causal estimate until conditions and starting charge are matched and runs repeated.", 12f, quiet))
            }
        }

        section("Your data", "Stays on this phone", "No account and no server. Export it any time.").apply {
            button("Export timestamped readings and tests · CSV") { export() }
            button("Record current phone battery now") {
                PhoneBattery.read(this@MainActivity, "manual")?.let(store::append)
                render()
            }
            addView(label("For Watch8 data, install the included Wear OS companion and open it once. " +
                "Watch sampling is scheduled approximately every 30 minutes.", 12f, quiet))
        }
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
            val readings = store.since(id, from)
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
        val recent = store.recent(id, 1000)
        val last = recent.lastOrNull()
        val now = System.currentTimeMillis()
        // The phone's own job can sleep for hours in Doze, so it gets a long allowance. A wearable
        // that misses three checks in a row, or drops out of Samsung's widget, is treated as offline:
        // its last number is history, not a live reading.
        val staleAfter = if (id == "phone") maxOf(6 * 3_600_000L, PollScheduler.gapLimits(this).stepMs)
            else maxOf(45 * 60_000L, 3L * PollScheduler.intervalMinutes(this) * 60_000L)
        val dropped = last?.source == SamsungWidgetSource.SOURCE && id in SamsungWidgetSource.missingDevices(this)
        val fresh = last != null && !dropped && now - last.timeMs <= staleAfter
        val projection = if (fresh) BatteryMath.estimate(recent.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, PollScheduler.gapLimits(this)) else null
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        val seenAt = last?.let {
            android.text.format.DateUtils.formatDateTime(this, it.timeMs, android.text.format.DateUtils.FORMAT_SHOW_TIME or
                if (android.text.format.DateUtils.isToday(it.timeMs)) 0
                else android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
        }
        val (status, statusColor) = when {
            last == null -> "No data yet" to Palette.quiet
            !fresh -> "Offline" to Palette.amberInk
            last.charging == true -> "Charging" to Palette.green
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
                        if (projection != null) add("about ${f1(projection.percentPerHour)}% an hour")
                    }.joinToString(" · ").ifEmpty { "Waiting for the first reading" }
                    textSize = 11.5f; setTextColor(Palette.quiet)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
                // Count down from the reading's time, not from now: an old reading means less left.
                val (value, unit) = if (projection != null && last != null) BatteryMath.timeLeftLabel(
                    BatteryMath.timeLeftNow(projection.hoursRemaining, last.timeMs, System.currentTimeMillis()))
                else "—" to "hours left"
                addView(TextView(this@MainActivity).apply {
                    text = value
                    textSize = 28f; typeface = medium; setTextColor(ink)
                })
                addView(TextView(this@MainActivity).apply {
                    text = unit
                    textSize = 11f; setTextColor(Palette.quiet)
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

    /** Samsung renders asynchronously after binding/resizing; read shortly afterwards. */
    private fun afterWidgetReady() {
        toast("Samsung Battery widget connected")
        render()
        body.postDelayed({ refreshWidget(); render() }, 1500)
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
        val names = devices.map { "${it.name ?: "Unnamed"}  (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose your already-paired Galaxy Ring")
            .setItems(names) { _, which ->
                if (store.activeExperiment() != null) {
                    toast("Finish the current experiment before changing Ring")
                    return@setItems
                }
                PollScheduler.setRingEnabled(this, false)
                PollScheduler.prefs(this).edit().putString(PollScheduler.RING_ADDRESS, devices[which].address).apply()
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

    /** Prefers sources that do not wake the Ring: Samsung widget, then a recent manual entry, then BLE. */
    private fun freshRingReading(next: (Reading) -> Unit) {
        refreshWidget()
        val ringId = PollScheduler.ringDeviceId(this)
        val latest = store.latest(ringId)
        val age = latest?.let { System.currentTimeMillis() - it.timeMs } ?: Long.MAX_VALUE
        when {
            latest != null && latest.source == SamsungWidgetSource.SOURCE && age <= 120_000 -> next(latest)
            latest != null && latest.source == "manual_widget" && age <= 120_000 -> next(latest)
            PollScheduler.ringAddress(this) != null -> readRing(next)
            else -> toast("No current Ring reading. Label the Ring in the Samsung widget section or enter it manually.")
        }
    }

    private fun startTest(mode: String) {
        if (store.activeExperiment() != null) { toast("Finish current test first"); return }
        if (mode == "monitoring" && PollScheduler.ringAddress(this) == null) {
            toast("A monitored test polls the Ring over BLE. Choose your paired Ring first.")
            return
        }
        PollScheduler.setRingEnabled(this, false)
        freshRingReading { reading ->
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
        PollScheduler.setRingEnabled(this, false)
        freshRingReading { reading ->
            store.finishExperiment(reading)
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
            afterWidgetReady()
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
class BatteryGraph(context: android.content.Context, private val series: List<List<Reading>>,
                   private val colors: List<Int>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val left = 30f * d
        val right = w - 6f * d
        val top = 8f * d
        val bottom = h - 22f * d
        val end = System.currentTimeMillis()
        val start = end - 48L * 3_600_000L
        fun x(t: Long) = left + (right - left) * (t - start).toFloat() / (end - start)
        fun y(pct: Int) = bottom - (bottom - top) * pct / 100f
        paint.textSize = 10.5f * d
        for (pct in listOf(0, 50, 100)) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Palette.hairline
            canvas.drawLine(left, y(pct), right, y(pct), paint)
            paint.style = Paint.Style.FILL; paint.color = Palette.quiet
            canvas.drawText("$pct%", 0f, y(pct) + 4f * d, paint)
        }
        series.forEachIndexed { index, data ->
            val recent = data.filter { it.timeMs in start..end }
            if (recent.isEmpty()) return@forEachIndexed
            paint.color = colors[index % colors.size]
            paint.strokeWidth = 2.5f * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.style = Paint.Style.STROKE
            val path = Path()
            recent.forEachIndexed { i, r ->
                if (i == 0 || r.timeMs - recent[i - 1].timeMs > 8 * 3_600_000L) path.moveTo(x(r.timeMs), y(r.percent))
                else path.lineTo(x(r.timeMs), y(r.percent))
            }
            canvas.drawPath(path, paint)
            val lastR = recent.last()
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(x(lastR.timeMs), y(lastR.percent), 5f * d, paint)
            paint.color = colors[index % colors.size]
            canvas.drawCircle(x(lastR.timeMs), y(lastR.percent), 3.5f * d, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Palette.quiet
        paint.textSize = 10.5f * d
        canvas.drawText("2 days ago", left, h - 4f * d, paint)
        canvas.drawText("yesterday", (left + right) / 2 - 22f * d, h - 4f * d, paint)
        canvas.drawText("now", right - 20f * d, h - 4f * d, paint)
    }
}
