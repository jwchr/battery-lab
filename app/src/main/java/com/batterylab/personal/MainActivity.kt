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

class MainActivity : Activity() {
    private lateinit var store: BatteryStore
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private var reader: RingReader? = null
    private val ink = Color.rgb(232, 240, 250)
    private val quiet = Color.rgb(155, 174, 199)
    private val bg = Color.rgb(12, 20, 34)
    private val panel = Color.rgb(22, 34, 52)
    private val blue = Color.rgb(95, 172, 255)
    private val warn = Color.rgb(255, 183, 77)
    private val REQUEST_BLUETOOTH = 108
    private val EXPORT_CSV = 109
    private val BIND_WIDGET = 110
    private val CONFIGURE_WIDGET = 111
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var widgetSnapshot: WidgetSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        store = BatteryStore(this)
        pendingWidgetId = savedInstanceState?.getInt("pendingWidgetId", pendingWidgetId) ?: pendingWidgetId
        PhoneBattery.read(this)?.let(store::append)
        PollScheduler.schedule(this)

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(bg)
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized && ::body.isInitialized) {
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
            SamsungWidgetSource.snapshot(this)?.also { SamsungWidgetSource.record(this, store, it) }
        } catch (e: Exception) {
            store.logProbe(SamsungWidgetSource.SOURCE, "Widget read failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    private fun label(text: String, size: Float = 15f, color: Int = ink, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(3), 0, dp(5))
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun makeButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        isAllCaps = false
        this.text = text
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(39, 64, 95))
            cornerRadius = dp(11).toFloat()
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun section(title: String, description: String? = null): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(15))
            background = GradientDrawable().apply {
                setColor(panel)
                cornerRadius = dp(16).toFloat()
            }
        }
        card.addView(label(title, 19f, ink, true))
        if (description != null) card.addView(label(description, 13f, quiet))
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        return card
    }

    private fun LinearLayout.button(text: String, onClick: () -> Unit) {
        addView(makeButton(text, onClick), LinearLayout.LayoutParams(-1, dp(47)).apply {
            topMargin = dp(7)
        })
    }

    private fun render() {
        val oldScroll = scroll.scrollY
        body.removeAllViews()
        body.addView(label("Battery Lab", 29f, ink, true))
        body.addView(label("Phone • Watch8 • Galaxy Ring", 15f, quiet))
        body.addView(label("Local, timestamped battery history. All predictions are estimates.", 13f, quiet))

        val ringAddress = PollScheduler.ringAddress(this)
        val ringId = PollScheduler.ringDeviceId(this)
        val watchId = store.deviceIds().filter { it.startsWith("watch:") }
            .maxByOrNull { store.latest(it)?.timeMs ?: 0L }
        val devices = listOf("phone" to "Phone", (watchId ?: "watch:unknown") to "Galaxy Watch8",
            ringId to "Galaxy Ring")
        for ((id, name) in devices) {
            val recent = store.recent(id, 1000)
            val last = recent.lastOrNull()
            val projection = if (last != null && System.currentTimeMillis() - last.timeMs <= 6 * 3_600_000L)
                BatteryMath.estimate(recent.map { BatteryPoint(it.timeMs, it.percent, it.charging) }) else null
            section("$name   ${last?.percent?.let { "$it%" } ?: "—"}",
                last?.let { "${BatteryStore.format(it.timeMs)} • ${if (it.charging == true) "Charging" else if (it.charging == false) "Not charging" else "Charge state unknown"} • ${sourceName(it.source)}" }
                    ?: "No readings yet").apply {
                val prediction = if (projection != null) {
                    "Observed drain: ${f(projection.percentPerHour)} pp/hour · " +
                        "~${f(projection.hoursRemaining)}h remaining · ~${f(projection.hoursFromFull)}h from full"
                } else "Runtime prediction needs ≥4 percentage points of discharge over ≥1 hour."
                addView(label(prediction, 14f, quiet))
                if (id == "phone" && last?.remainingUah != null)
                    addView(label("Reported remaining charge: ${last.remainingUah / 1000} mAh (not battery health)", 12f, quiet))
            }
        }

        section("History · last 48 hours", "Gaps are real gaps; Android can defer background jobs.").apply {
            addView(BatteryGraph(this@MainActivity, devices.map { (id, _) -> store.recent(id, 4000) }),
                LinearLayout.LayoutParams(-1, dp(185)))
            addView(label("Blue: phone   •   Green: watch   •   Orange: ring", 12f, quiet))
        }

        renderWidgetSection()

        section("Galaxy Ring direct BLE (optional fallback)", "Only needed if the Samsung widget source is unavailable. " +
            "Each request wakes the Ring. Battery-only BLE requests.").apply {
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

        section("Controlled battery-impact experiment", "Run baseline and monitored periods for similar durations, ideally starting near the same charge percentage. Repeat in alternating order.").apply {
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

        section("Data & privacy", "Everything is stored on this device. No account and no remote server.").apply {
            button("Export timestamped readings and tests · CSV") { export() }
            button("Record current phone battery now") {
                PhoneBattery.read(this@MainActivity)?.let(store::append)
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

    private fun renderWidgetSection() {
        val connected = SamsungWidgetSource.widgetId(this) != null
        section("Samsung Battery widget source", "Reads the percentages Samsung's Battery widget already shows. " +
            "No Bluetooth, no extra Ring or Watch wakeups. Checked about every 15 minutes and whenever you open Battery Lab.").apply {
            // Soak test: without the exemption, Adaptive Battery demoted the app to RARE and the
            // 15-minute job stopped for hours. Ignoring optimizations needs no permission to check.
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (!power.isIgnoringBatteryOptimizations(packageName)) {
                addView(label("Background checks may stop for hours: set Battery Lab's battery usage to " +
                    "Unrestricted (App info → Battery → Unrestricted).", 13f, warn, true))
                button("Open Battery Lab app info") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)))
                }
            }
            if (!connected) {
                addView(label("Not connected. Android will ask you to allow Battery Lab to use the widget.", 13f, quiet))
                button("Connect Samsung Battery widget") { connectWidget() }
                return@apply
            }
            val snap = widgetSnapshot
            if (snap == null || snap.slots.isEmpty()) {
                addView(label("Connected, but no battery values were readable yet. Tap Read now in a few seconds.", 13f, quiet))
            } else {
                val phone = PhoneBattery.read(this@MainActivity)
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
            SamsungWidgetSource.LABEL_PHONE -> "This phone (not recorded)"
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
                widgetSnapshot?.let { SamsungWidgetSource.record(this, store, it) }
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
                store.logProbe("ring:$address", error ?: "Unknown BLE error")
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
                store.append(Reading(PollScheduler.ringDeviceId(this), System.currentTimeMillis(), pct, null, "manual_widget"))
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
        if (requestCode == EXPORT_CSV && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use(store::exportCsv)
                toast("CSV exported")
            } catch (e: Exception) { toast("CSV export failed: ${e.message}") }
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
class BatteryGraph(context: android.content.Context, private val series: List<List<Reading>>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val left = 35f * resources.displayMetrics.density
        val right = w - 8f * resources.displayMetrics.density
        val top = 12f * resources.displayMetrics.density
        val bottom = h - 23f * resources.displayMetrics.density
        val start = System.currentTimeMillis() - 48L * 3_600_000L
        val end = System.currentTimeMillis()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.rgb(67, 80, 99)
        for (pct in listOf(0, 25, 50, 75, 100)) {
            val y = bottom - (bottom - top) * pct / 100f
            canvas.drawLine(left, y, right, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(162, 177, 198)
            paint.textSize = 11f * resources.displayMetrics.scaledDensity
            canvas.drawText("$pct", 2f, y + 4f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(67, 80, 99)
        }
        val colors = listOf(Color.rgb(95, 172, 255), Color.rgb(99, 213, 166), Color.rgb(255, 172, 92))
        series.forEachIndexed { index, data ->
            val recent = data.filter { it.timeMs in start..end }
            if (recent.isEmpty()) return@forEachIndexed
            paint.color = colors[index % colors.size]
            paint.strokeWidth = 2.5f * resources.displayMetrics.density
            paint.style = Paint.Style.STROKE
            val path = Path()
            recent.forEachIndexed { i, reading ->
                val x = left + (right - left) * (reading.timeMs - start).toFloat() / (end - start)
                val y = bottom - (bottom - top) * reading.percent / 100f
                if (i == 0) path.moveTo(x, y) else {
                    if (reading.timeMs - recent[i - 1].timeMs > 8 * 3_600_000L) path.moveTo(x, y)
                    else path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            for (reading in recent) {
                val x = left + (right - left) * (reading.timeMs - start).toFloat() / (end - start)
                val y = bottom - (bottom - top) * reading.percent / 100f
                canvas.drawCircle(x, y, 2f * resources.displayMetrics.density, paint)
            }
        }
        paint.color = Color.rgb(155, 174, 199)
        paint.textSize = 11f * resources.displayMetrics.scaledDensity
        paint.style = Paint.Style.FILL
        canvas.drawText("48h ago", left, h - 4f, paint)
        canvas.drawText("now", right - 23f * resources.displayMetrics.density, h - 4f, paint)
    }
}
