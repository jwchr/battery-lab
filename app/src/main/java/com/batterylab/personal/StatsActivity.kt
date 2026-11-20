package com.batterylab.personal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** A page of battery statistics for the curious: health, habits, patterns and fun conversions. */
class StatsActivity : StyledActivity() {
    private lateinit var store: BatteryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BatteryStore.get(this)
        setUpPage()
        render()
    }

    private var resumedOnce = false

    /** Coming back (for example after granting Usage access) shows current numbers. */
    override fun onResume() {
        super.onResume()
        if (resumedOnce && pageReady) render()
        resumedOnce = true
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }

    /** Everything the page needs from the database and the heavy math, computed off the main thread. */
    private class Loaded(
        val devices: List<Triple<String, String, Int>>,
        val data: List<List<Reading>>,
        val gaps: GapLimits,
        val stats: List<DeviceStats>,
        val capacity: CapacityEstimate,
        val usage: UsageWindow?,
        val idle: List<IdleStretch>,
        val phoneWidget: List<Reading>,
        val drain: DrainPrep?,
        val forecasts: List<Forecast>,
        /** Watch-app readings, compared against the widget's in the audit. */
        val watchCompanion: List<Reading>,
        /** Per device (same order as [devices]): each window's label and coverage, worked out off the main thread. */
        val coverage: List<List<Pair<String, Coverage>>>,
        /** Per device: drain spread over the last 30 days, or null with too few stretches. */
        val spread: List<DrainSpread?>,
        /** Per device: weekday and weekend drain over the last 30 days, or null with too little data. */
        val week: List<WeekSplit?>,
    )

    /** The drain breakdown and the app names it shows, prepared off the main thread. */
    private class DrainPrep(val recent: List<Reading>, val d: DrainBreakdown, val labels: Map<String, String>, val hidden: Int?)

    @Volatile private var renderGeneration = 0

    override fun iconFor(eyebrow: String): String? = SECTION_ICONS[eyebrow]

    /**
     * Audit #4 A6: loading every reading and running the stats math used to block the main thread,
     * which grows with history. It now runs on [Background.io]; only view building stays here.
     * A newer render (resume, journal edit) supersedes an older one still loading.
     */
    private fun render() {
        val generation = ++renderGeneration
        if (body.childCount == 0) {
            renderTitle()
            body.addView(label("Crunching your history…", 13f, Palette.quiet).apply { setPadding(dp(6), dp(4), dp(6), 0) })
        }
        Background.io.execute {
            // A newer render or a closed screen makes this load pointless; skip it before the work.
            if (isDestroyed || generation != renderGeneration) return@execute
            val started = android.os.SystemClock.elapsedRealtime()
            val loaded = try { load() } catch (e: Exception) {
                android.util.Log.w(TAG, "Stats load failed", e); null
            }
            val loadMs = android.os.SystemClock.elapsedRealtime() - started
            Background.onMain {
                if (isDestroyed || generation != renderGeneration) return@onMain
                val shown = android.os.SystemClock.elapsedRealtime()
                if (loaded == null) {
                    body.removeAllViews(); renderTitle()
                    body.addView(label("Couldn't read the stored history. Try again in a moment.", 13f, Palette.quiet))
                    return@onMain
                }
                show(loaded)
                android.util.Log.i(TAG, "Stats: ${loaded.data.sumOf { it.size }} readings, load ${loadMs} ms (background), " +
                    "views ${android.os.SystemClock.elapsedRealtime() - shown} ms (main)")
            }
        }
    }

    private fun load(): Loaded {
        val ringId = PollScheduler.ringDeviceId(this)
        val watchId = store.primaryWatchId()
        val devices = listOf(Triple("phone", "Phone", Palette.phone),
            Triple(watchId ?: "watch:unknown", "Galaxy Watch8", Palette.watch), Triple(ringId, "Galaxy Ring", Palette.ring))
        val hourOf = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY) }
        val data = devices.map { (id, _, _) -> BatteryStats.fullHoldAsCharging(store.since(id, 0L)) }
        val gaps = PollScheduler.gapLimits(this)
        val stats = data.map { BatteryStats.device(it, hourOf, gaps) }
        val phone = data[0]
        val capacity = HonestyAuditor.estimatePhoneCapacity(phone, gaps)
        val now = System.currentTimeMillis()
        val stretches = data.map { readings ->
            BatteryMath.dischargeStretches(readings.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = gaps) }
        val isWeekend = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)
            .let { it == Calendar.SATURDAY || it == Calendar.SUNDAY } }
        val usage = if (UsageSource.granted(this)) UsageSource.load(this, now - 7 * 86_400_000L, now) else null
        val idle = IdleAnalysis.stretches(phone, usage?.screenOn, gaps = gaps, screenCoverageStartMs = usage?.coverageStartMs)
        // The drain analysis and package-manager label lookups took ~116 ms on the main thread.
        val drain = usage?.let { u ->
            val start = u.coverageStartMs
            val recent = if (start == null) emptyList() else phone.filter { it.timeMs >= start }
            val d = DrainAnalysis.analyze(recent.map(BatteryPoint::of), u.screenOn, u.apps, gaps.stepMs,
                capacity.combinedMah, u.services)
            DrainPrep(recent, d, d.apps.take(8).associate { it.app to UsageSource.label(this, it.app) },
                UsageSource.hiddenCount(this))
        }
        return Loaded(devices, data, gaps, stats, capacity, usage, idle,
            store.since(SamsungWidgetSource.PHONE_WIDGET_DEVICE, 0L), drain, store.forecasts(),
            if (watchId == SamsungWidgetSource.WATCH_DEVICE) store.companionWatchReadings(0L) else emptyList(),
            data.map { readings -> coverageRows(readings.map { it.timeMs }, now, gaps) },
            stretches.map { DrainSpreadMath.of(it, now - 30 * 86_400_000L) },
            stretches.map { WeekSplitMath.of(it, now - 30 * 86_400_000L, isWeekend) })
    }

    private fun renderTitle() {
        body.addView(TextView(this).apply {
            text = "←  Back"
            textSize = 14f; typeface = medium; setTextColor(ink)
            setPadding(dp(14), dp(8), dp(16), dp(8))
            background = rounded(Color.argb(215, 255, 253, 248), 14, Palette.cardStroke)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
        body.addView(TextView(this).apply {
            text = "Stats for the curious."
            textSize = 28f; typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.02f
            setTextColor(ink); setPadding(dp(4), 0, 0, dp(14))
        })
    }

    private fun show(l: Loaded) {
        val oldScroll = scroll.scrollY
        body.removeAllViews()
        renderTitle()
        val devices = l.devices; val data = l.data; val gaps = l.gaps; val stats = l.stats
        val capacity = l.capacity; val usage = l.usage; val idle = l.idle; val phone = data[0]

        val sectionMs = mutableListOf<Pair<String, Long>>()
        fun timed(name: String, block: () -> Unit) {
            val t0 = android.os.SystemClock.elapsedRealtime(); block()
            sectionMs += name to android.os.SystemClock.elapsedRealtime() - t0
        }
        timed("health") { renderPhoneHealth(phone, capacity) }
        timed("planner") { renderPlanner(devices, data, gaps) }
        timed("drain") { renderDrain(l.drain) }
        timed("nights") { renderNights(idle) }
        timed("charge times") { renderChargeTimes(devices, data, gaps) }
        timed("charging") { renderCharging(phone, gaps) }
        timed("audit") { renderAudit(phone, l.phoneWidget, (data[1] + l.watchCompanion).sortedBy { it.timeMs }, data[2], capacity, gaps) }
        timed("forecasts") { renderForecasts(l.forecasts, l.devices) }
        timed("journal") { renderJournal(idle) }
        timed("heat") { renderHeat(phone, gaps) }
        timed("numbers") { renderNumbers(devices, stats) }
        timed("habits") { renderHabits(stats[0]) }
        timed("hours") { renderHours(devices, stats) }
        timed("showdown") { renderShowdown(devices, data) }
        timed("headtohead") { renderHeadToHead(devices, stats) }
        timed("rolling") { renderRolling(devices, data) }
        timed("spread") { renderSpread(devices, l.spread) }
        timed("week") { renderWeek(devices, l.week) }
        timed("temperature") { BatteryStats.temperature(phone)?.let(::renderTemperature) }
        // The same capacity figure as the Phone battery capacity headline.
        timed("fun") { renderFun(stats[0], phoneCapacity(phone)?.first ?: capacity.combinedMah, phone) }
        timed("coverage") { renderCoverage(devices, l.coverage, gaps) }
        timed("overhead") { renderOverhead() }
        timed("capabilities") { renderCapabilities() }
        // Which sections still cost main-thread time (audit #4 A6); only the slow ones are listed.
        android.util.Log.i(TAG, "Stats sections (main, >=5 ms): " +
            sectionMs.filter { it.second >= 5 }.sortedByDescending { it.second }.joinToString { "${it.first} ${it.second}" }.ifEmpty { "none" })
        body.addView(label("All numbers come from readings stored on this phone. Estimates, not lab measurements.",
            11.5f, Palette.body).apply { setPadding(dp(6), dp(4), dp(6), 0) })
        scroll.post { scroll.scrollTo(0, oldScroll) }
    }

    /**
     * Estimated full capacity in mAh and when it was observed: Android's charge counter at the
     * latest 100% reading, else the median implied by partial readings. Only the last 30 days
     * count, so an old reading can't stand in for today's battery.
     */
    private fun phoneCapacity(phone: List<Reading>): Pair<Double, Long>? {
        val recent = phone.filter { it.timeMs >= System.currentTimeMillis() - 30 * 86_400_000L }
        recent.lastOrNull { it.percent == 100 && (it.remainingUah ?: 0) > 0 }?.let { return it.remainingUah!! / 1000.0 to it.timeMs }
        val implied = recent.mapNotNull { r -> BatteryMath.impliedCapacityMah(r.percent, r.remainingUah, r.charging)?.let { it to r.timeMs } }
        return BatteryMath.median(implied.map { it.first })?.let { it to implied.last().second }
    }

    private fun renderPhoneHealth(phone: List<Reading>, lab: CapacityEstimate) {
        val estimate = phoneCapacity(phone)
        // The recent (30-day) estimate first, so the headline and its caption describe one figure.
        val capacity = estimate?.first ?: lab.combinedMah
        val spec = PhoneSpecs.forModel(Build.MODEL)
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val cycles = if (Build.VERSION.SDK_INT >= 34) sticky?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it > 0 } else null
        val health = when (sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }
        val pct = if (capacity != null && spec != null) capacity / spec.typicalMah * 100 else null
        section("Phone battery capacity", capacity?.let { "About ${fmt(it)} mAh when full." } ?: "Measuring\u2026",
            tag = health).apply {
            addView(LinearLayout(this@StatsActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(4))
                addView(GaugeView(this@StatsActivity, pct?.roundToInt()?.coerceAtMost(100), Palette.green),
                    LinearLayout.LayoutParams(dp(84), dp(84)))
                addView(label(buildString {
                    if (pct != null) append("Roughly ${String.format(Locale.US, "%.0f", pct)}% of the ${fmt(spec!!.typicalMah)} mAh advertised" +
                        if (pct > 100.5) ". New batteries often hold a little more than the label" else "")
                    else if (spec != null) append("Advertised: ${fmt(spec.typicalMah)} mAh")
                    if (spec != null) append(" (${fmt(spec.ratedMah)} mAh rated minimum).")
                    if (estimate != null) append("\nEstimated from Android's charge counter, ${shortWhen(estimate.second)}. " +
                        "A rough guide, not a measured health test.")
                    else if (capacity != null) append("\nNo reading in the last 30 days; estimated from older charge-counter readings.")
                }, 13f, Palette.body).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            val tiles = mutableListOf<Triple<String, String, String?>>()
            if (cycles != null) tiles += Triple("$cycles", if (cycles == 1) "charge cycle" else "charge cycles", "as counted by Android")
            sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.let { tiles += Triple(it, "chemistry", null) }
            sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.let {
                tiles += Triple(String.format(Locale.US, "%.2f V", it / 1000.0), "voltage now", null)
            }
            sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let {
                tiles += Triple("${String.format(Locale.US, "%.1f", it / 10.0)}°C", "temperature now", null)
            }
            addTiles(tiles)
            if (lab.sampleCount > 0) addView(label("Full-charge readings: ${lab.saturatedPeakMah?.let { "${fmt(it)} mAh" } ?: "none yet"} (${lab.peakSamples}) \u00b7 " +
                "slope method: ${lab.slopeCapacityMah?.let { "${fmt(it)} mAh" } ?: "not enough yet"} (${lab.slopeSamples})" +
                (lab.iqrMah?.let { " \u00b7 spread \u00b1${fmt(it / 2)} mAh" } ?: ""), 11.5f, Palette.quiet).apply { setPadding(0, dp(8), 0, 0) })
            evidence("Android's charge counter (mAh left) and percent, from this phone's readings.",
                "The headline is the last 30 days: the counter at the latest 100% reading, else the median capacity implied " +
                    "by partial readings. With no reading in 30 days it falls back to two longer-run methods: the counter at " +
                    "settled 100% plateaus, and the slope of counter vs percent over each discharge (15\u201398%) \u00d7 100, " +
                    "taking the median of all samples from both.",
                "${lab.sampleCount} samples. Plateaus need the charger to have finished; slopes need a 30-point stretch with 6+ readings.")
            if (spec == null) addView(label("Comparing to new needs this model's factory capacity, which Battery Lab doesn't know yet.", 11.5f, Palette.quiet))
        }
    }

    private fun renderNumbers(devices: List<Triple<String, String, Int>>, stats: List<DeviceStats>) {
        val first = stats.mapNotNull { it.firstMs }.minOrNull()
        val days = first?.let { (System.currentTimeMillis() - it) / 86_400_000.0 } ?: 0.0
        section("By the numbers", "Since Battery Lab started watching.").apply {
            addTileList(listOf(
                Tile(if (days < 2) "${(days * 24).roundToInt()} h" else "${f1(days)} days", "tracked", null, "\uD83D\uDCC5"),
                Tile(fmt(stats.sumOf { it.readings }.toDouble()), "readings saved",
                    "about ${fmt(stats.sumOf { it.readings } / maxOf(days, 1.0 / 24))} a day", "\uD83D\uDCBE"),
            ))
            devices.forEachIndexed { i, (_, name, color) ->
                val s = stats[i]
                if (s.readings == 0) return@forEachIndexed
                addView(deviceBanner(name, color))
                addTileList(listOfNotNull(
                    Tile(f1(s.fullBatteriesUsed), "full batteries used", "${s.percentUsed} points in all", "\uD83D\uDD0B"),
                    Tile("${s.charges.size}", if (s.charges.size == 1) "charge spotted" else "charges spotted", null, "\uD83D\uDD0C"),
                    s.lowest?.let { Tile("${it.first}%", "lowest seen", "closest call · ${shortWhen(it.second)}", "\uD83E\uDEAB") },
                    Tile("${f1(s.longestOnBatteryHours)} h", "longest unplugged", null, "\u23F1\uFE0F"),
                ))
            }
        }
    }

    private fun renderHabits(phone: DeviceStats) {
        if (phone.charges.isEmpty() && phone.hoursAtOrAbove80 <= 0.0) return
        section("Charging habits", "How you charge your phone.").apply {
            val fast = phone.fastestCharge
            addTileList(listOfNotNull(
                phone.averagePlugIn?.let { Tile("${it.roundToInt()}%", "average plug-in", "where you usually reach for the charger", "\uD83D\uDD0C") },
                phone.averageUnplug?.let { Tile("${it.roundToInt()}%", "average unplug", null, "\uD83D\uDE80") },
                fast?.let { Tile("${(60 / it.ratePerMinute!!).roundToInt()} s", "per 1% at best", "fastest charge · ${shortWhen(it.startMs)}", "\u26A1") },
                Tile("${f1(phone.hoursAt100)} h", "parked at 100%", "time plugged in and already full", "\uD83C\uDD7F\uFE0F"),
                Tile("${f1(phone.hoursAtOrAbove80)} h", "at 80% or more", "plugged in or not", "\uD83D\uDD1D"),
                phone.partialChargeShare?.let { Tile("${(it * 100).roundToInt()}%", "of charges stopped short",
                    "unplugged before 100% (watched charges only)", "\u23F8\uFE0F") },
                Tile("${f1(phone.hoursBelow20)} h", "spent under 20%", "living dangerously", "\uD83D\uDE2C"),
                Tile("${pctOf(phone.hoursCharging, phone.hoursCharging + phone.hoursOnBattery)}%", "of time charging", null, "\uD83D\uDD52"),
            ))
        }
    }

    private fun renderHours(devices: List<Triple<String, String, Int>>, stats: List<DeviceStats>) {
        section("Around the clock", "Which hours drain the most.", "Average % per hour by time of day. Taller is hungrier.").apply {
            devices.forEachIndexed { i, (_, name, color) ->
                val s = stats[i]
                if (s.drainByHour.all { it == null }) return@forEachIndexed
                addView(deviceBanner(name, color))
                addView(HourBars(this@StatsActivity, s.drainByHour, color), LinearLayout.LayoutParams(-1, dp(70)))
                val hungry = s.hungriestHour; val calm = s.calmestHour
                if (hungry != null && calm != null) addView(label("Hungriest at ${hourName(hungry)} (${f1(s.drainByHour[hungry]!!)}%/h)" +
                    (if (calm != hungry) " · calmest at ${hourName(calm)} (${f1(s.drainByHour[calm]!!)}%/h)" else ""), 12f, Palette.quiet))
            }
        }
    }

    /**
     * Phone vs Watch vs Ring on everyday terms: battery used per day, charges per week and the
     * usual time between charges. Each device is measured over its own tracked span, at least 2 days.
     */
    private fun renderHeadToHead(devices: List<Triple<String, String, Int>>, stats: List<DeviceStats>) {
        data class Row(val name: String, val color: Int, val perDay: Double, val perWeek: Double, val between: Double?)
        val rows = devices.mapIndexedNotNull { i, (_, name, color) ->
            val s = stats[i]
            val first = s.firstMs ?: return@mapIndexedNotNull null
            val last = s.lastMs ?: return@mapIndexedNotNull null
            val days = (last - first) / 86_400_000.0
            if (days < 2.0) return@mapIndexedNotNull null
            val starts = s.charges.map { it.startMs }.sorted()
            val between = BatteryMath.median(starts.zipWithNext { a, b -> (b - a) / 3_600_000.0 })
            Row(name, color, s.percentUsed / days, s.charges.size / days * 7, between)
        }
        if (rows.size < 2) return
        section("Head to head", "Phone, Watch and Ring side by side.",
            "Each over its own tracked days. Battery used counts every observed drop, so 100% a day means one full battery.").apply {
            val most = rows.maxOf { it.perDay }.coerceAtLeast(1.0)
            addView(eyebrow("Battery used per day").apply { setPadding(0, dp(10), 0, dp(2)) })
            rows.sortedByDescending { it.perDay }.forEach { r ->
                addView(barRow(r.name.uppercase(Locale.getDefault()), r.perDay / most, "${r.perDay.roundToInt()}%", r.color,
                    "${f1(r.perDay / 100)} full batteries a day"))
            }
            addView(eyebrow("Charging").apply { setPadding(0, dp(12), 0, 0) })
            addTileList(rows.map { r ->
                Tile(f1(r.perWeek), "charges a week", r.name + (r.between?.let { " · usually ${durationHours(it)} apart" } ?: ""),
                    when { r.name.contains("Ring", true) -> "\uD83D\uDC8D"; r.name.contains("Watch", true) -> "\u231A"; else -> "\uD83D\uDCF1" })
            })
        }
    }

    private fun durationHours(h: Double) = if (h >= 48) "${f1(h / 24)} days" else "${h.roundToInt()} h"

    private fun renderShowdown(devices: List<Triple<String, String, Int>>, data: List<List<Reading>>) {
        val lasting = devices.mapIndexedNotNull { i, (_, name, color) ->
            val stretches = BatteryMath.dischargeStretches(data[i].map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = PollScheduler.gapLimits(this))
            if (stretches.isEmpty()) null else Triple(name, color,
                100.0 * stretches.sumOf { it.hours } / stretches.sumOf { it.drop })
        }.sortedByDescending { it.third }
        if (lasting.size < 2) return
        section("Showdown", "Who lasts longest on a charge?").apply {
            val top = lasting.first().third
            lasting.forEachIndexed { i, (name, color, hours) ->
                addView(barRow(listOf("1ST", "2ND", "3RD")[i], hours / top, "${hours.roundToInt()} h", color, name))
            }
        }
    }

    /** Full-charge runtime averaged over rolling windows, so recent use can be compared with the long run. */
    private fun renderRolling(devices: List<Triple<String, String, Int>>, data: List<List<Reading>>) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val windows = listOf("7 DAYS" to 7 * day, "30 DAYS" to 30 * day, "6 MONTHS" to 182 * day, "1 YEAR" to 365 * day, "ALL TIME" to Long.MAX_VALUE / 2)
        section("Over time", "How long a full charge would last at the drain rate seen.", "Projected 100% → 0% from " +
            "the observed stretches of discharge in each window (each at least an hour long, not full cycles), weighted by " +
            "how much battery each used. Longer windows fill in as history grows.").apply {
            devices.forEachIndexed { i, (_, name, color) ->
                val stretches = BatteryMath.dischargeStretches(data[i].map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = PollScheduler.gapLimits(this@StatsActivity))
                if (stretches.isEmpty()) return@forEachIndexed
                val first = data[i].first().timeMs
                val rows = windows.mapNotNull { (labelText, span) ->
                    val inWindow = BatteryMath.overlapping(stretches, now - span)
                    val (winHours, winDrop) = BatteryMath.windowTotals(stretches, now - span)
                    if (inWindow.isEmpty() || winDrop <= 0) null else Triple(labelText, 100.0 * winHours / winDrop,
                        if (now - first < span && span < Long.MAX_VALUE / 2) "only ${f1((now - first) / day.toDouble())} days so far" else "${inWindow.size} observed stretch${if (inWindow.size == 1) "" else "es"}")
                }
                if (rows.isEmpty()) return@forEachIndexed
                addView(deviceBanner(name, color))
                val top = rows.maxOf { it.second }
                rows.forEach { (w, hours, cap) -> addView(barRow(w, hours / top, "${hours.roundToInt()} h", color, cap)) }
            }
        }
    }

    /** Audit #4 P0: how much of each recent window every device was actually read. */
    /** Audit #4 roadmap: how much the drain rate swings between stretches, worked out in [load]. */
    private fun renderSpread(devices: List<Triple<String, String, Int>>, spread: List<DrainSpread?>) {
        if (spread.all { it == null }) return
        section("Drain spread", "How much your drain rate swings from one stretch to the next.", "Each observed " +
            "stretch of discharge from the last 30 days (at least an hour and 4 points, the same stretches as Over time) " +
            "gives one rate in percent per hour. Typical is the median; the range runs from the 10th to the 90th " +
            "percentile, each the value at position ceil(p × n / 100) of the sorted rates. Needs at least " +
            "${DrainSpreadMath.MIN_STRETCHES} stretches.").apply {
            devices.forEachIndexed { i, (_, name, color) ->
                val d = spread[i] ?: return@forEachIndexed
                addView(deviceBanner(name, color))
                addView(label("Typical ${f1(d.median)}% per hour; most stretches ${f1(d.p10)} to ${f1(d.p90)}. " +
                    "Calmest ${f1(d.calmest.percentPerHour)}% per hour (${shortWhen(d.calmest.startMs)}, ${f1(d.calmest.hours)} h); " +
                    "hungriest ${f1(d.hungriest.percentPerHour)}% per hour (${shortWhen(d.hungriest.startMs)}, ${f1(d.hungriest.hours)} h).",
                    13f, Palette.body).apply { setPadding(0, dp(4), 0, dp(4)) })
                val top = d.bands.max().coerceAtLeast(1)
                d.bands.forEachIndexed { b, n ->
                    addView(barRow("${DrainSpreadMath.BAND_LABELS[b]}", n.toDouble() / top, "$n",
                        color, if (b == 0) "stretches by % per hour, ${d.stretches} in all" else null))
                }
            }
        }
    }

    /** Audit #4 roadmap: weekday and weekend drain where the readings support it, worked out in [load]. */
    private fun renderWeek(devices: List<Triple<String, String, Int>>, week: List<WeekSplit?>) {
        if (week.all { it == null }) return
        section("Weekdays vs weekends", "Whether your devices drain differently on Saturdays and Sundays.", "Each " +
            "observed stretch of discharge from the last 30 days goes to weekdays or weekends by the local day of its " +
            "midpoint. Each side's rate is its total points dropped over its total hours. A side shows only with at " +
            "least ${WeekSplitMath.MIN_STRETCHES} stretches and ${WeekSplitMath.MIN_HOURS.roundToInt()} hours. A " +
            "difference shows what happened, not why.").apply {
            devices.forEachIndexed { i, (_, name, color) ->
                val w = week[i] ?: return@forEachIndexed
                addView(deviceBanner(name, color))
                val top = listOfNotNull(w.weekdays, w.weekends).maxOf { it.percentPerHour }
                listOf("WEEKDAYS" to w.weekdays, "WEEKENDS" to w.weekends).forEach { (side, g) ->
                    if (g == null) addView(barRow(side, 0.0, "–", color, "not enough stretches yet"))
                    else addView(barRow(side, g.percentPerHour / top, "${f1(g.percentPerHour)}%/h", color,
                        "${g.stretches} stretch${if (g.stretches == 1) "" else "es"}, ${f1(g.hours)} h"))
                }
                w.weekendDifferencePct?.let { d ->
                    val text = when { d >= 5 -> "Weekends drain ${d.roundToInt()}% faster."
                        d <= -5 -> "Weekends drain ${(-d).roundToInt()}% slower."
                        else -> "About the same on weekends and weekdays." }
                    addView(label(text, 13f, Palette.body).apply { setPadding(0, dp(4), 0, dp(4)) })
                }
            }
        }
    }

    private fun coverageRows(times: List<Long>, now: Long, gaps: GapLimits): List<Pair<String, Coverage>> {
        val day = 86_400_000L
        return listOf("24 HOURS" to day, "7 DAYS" to 7 * day, "30 DAYS" to 30 * day)
            .mapNotNull { (w, span) -> CoverageMath.of(times, now - span, now, gaps.stepMs)?.let { w to it } }
    }

    private fun renderCoverage(devices: List<Triple<String, String, Int>>, coverage: List<List<Pair<String, Coverage>>>, gaps: GapLimits) {
        val limit = gapText(gaps.stepMs)
        val cards = devices.mapIndexedNotNull { i, (_, name, color) ->
            coverage[i].takeIf { it.isNotEmpty() }?.let { Triple(name, color, it) }
        }
        if (cards.isEmpty()) return
        section("Data coverage", "How much of the time each device was being read.", "A stretch counts as covered " +
            "when two readings are no more than $limit apart (2.5 times the check interval, and at least an hour). " +
            "Any longer step, including the time since the last reading, is a gap, and only its part inside the " +
            "window counts. A window that starts before a device's first reading starts at that reading instead. " +
            "Gaps are left out of every other number on this page.").apply {
            cards.forEach { (name, color, rows) ->
                addView(deviceBanner(name, color))
                rows.forEach { (w, c) ->
                    val gapsText = when (c.gaps) { 0 -> "no gaps"; 1 -> "1 gap of ${gapText(c.longestGapMs)}"
                        else -> "${c.gaps} gaps, longest ${gapText(c.longestGapMs)}" }
                    val since = if (c.startsAtFirstReading) " · since first reading" else ""
                    addView(barRow(w, c.percent / 100, "${c.percent.roundToInt()}%", color,
                        "$gapsText · ${c.readings} readings$since"))
                }
            }
        }
    }

    private fun gapText(ms: Long): String {
        val min = ms / 60_000.0
        return when { min < 90 -> "${min.roundToInt()} min"; min < 48 * 60 -> "${f1(min / 60)} h"; else -> "${f1(min / 1440)} days" }
    }

    private fun renderTemperature(t: TempStats) {
        section("Temperature", "How warm your phone runs.").apply {
            addTiles(listOfNotNull(
                Triple("${f1(t.avgC)}°C", "average", "${f1(t.avgC * 9 / 5 + 32)}°F"),
                Triple("${f1(t.maxC)}°C", "hottest", shortWhen(t.maxAtMs)),
                Triple("${f1(t.minC)}°C", "coolest", null),
                t.avgChargingC?.let { c -> Triple("${f1(c)}°C", "while charging",
                    t.avgOnBatteryC?.let { u -> "${f1(kotlin.math.abs(c - u))}° ${if (c >= u) "warmer" else "cooler"} than unplugged" }) },
            ))
        }
    }

    private fun renderFun(phone: DeviceStats, capacityMah: Double?, readings: List<Reading>) {
        val cap = capacityMah ?: return
        val wh = BatteryStats.wattHours(phone.percentUsed, cap)
        if (wh <= 0.0) return
        val days = readings.firstOrNull()?.let { (System.currentTimeMillis() - it.timeMs) / 86_400_000.0 } ?: 0.0
        val rate = centsPerKwh()
        val cents = wh / 1000 * rate
        val fullCharge = BatteryStats.wattHours(100, cap)
        section("What it costs", "${money(cents)} of electricity so far.",
            if (rateIsSet()) "At ${f1(rate)}\u00a2 per kWh, the rate you set." else
                "At ${f1(rate)}\u00a2 per kWh, a rough US average. Set your own rate from your bill." +
                    " Battery side only; charger losses add roughly 15\u201320% at the wall.").apply {
            addTileList(listOfNotNull(
                Tile(money(fullCharge / 1000 * rate), "per full charge", "${f1(fullCharge)} Wh", "\uD83D\uDCB5"),
                if (days >= 1.0) Tile(money(cents / days * 365), "a year at this pace", "for the phone", "\uD83D\uDCC6") else null,
                Tile(hoursOrMinutes(wh / 1000 / FRIDGE_KWH_PER_DAY * 24), "of a fridge", "a typical fridge uses about 1.5 kWh a day", "\uD83E\uDDCA"),
                Tile(distance(wh / EV_WH_PER_METER), "in an electric car", "at about 300 Wh per mile", "\uD83D\uDE97"),
            ))
            addView(makeButton(if (rateIsSet()) "Change your rate" else "Set your rate") { askRate() },
                LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        }
        section("Where it comes from", "What it takes to make ${f1(wh)} Wh.",
            "If all of it came from one source. Rough US averages, before charger losses.").apply {
            addTileList(listOf(
                Tile(mass(wh * COAL_G_PER_WH), "of coal", "burned in a power plant", "\u26AB"),
                Tile(volume(wh * GAS_L_PER_WH), "of natural gas", "at a gas power plant", "\uD83D\uDD25"),
                Tile(mass(wh * URANIUM_G_PER_WH), "of uranium", "nuclear fuel; a grain of salt is about 60 \u00b5g", "\u269B\uFE0F"),
                Tile(duration(wh / SOLAR_PANEL_W * 3600), "of full sun", "on one 400 W rooftop panel", "\u2600\uFE0F"),
                Tile(duration(wh / WIND_TURBINE_W * 3600), "of a wind turbine", "a 3 MW turbine at full speed", "\uD83C\uDF2C\uFE0F"),
                Tile(duration(wh / CYCLIST_W * 3600), "of pedaling", "a fit cyclist making 100 W", "\uD83D\uDEB4"),
                Tile(mass(wh * CO2_G_PER_WH), "of CO\u2082", "at the US grid average", "\uD83C\uDF2B\uFE0F"),
            ))
        }
        section("Just for fun", "Your phone has used ${f1(wh)} Wh.",
            "Energy that went through the battery since tracking started, at a nominal 3.87 V.").apply {
            eyebrowRow("Around the house")
            addTileList(listOf(
                Tile(f1(wh / LED_BULB_W), "LED bulb hours", "a 9 W bulb, the 60 W kind's replacement", "\uD83D\uDCA1"),
                Tile(f1(wh / OLD_BULB_W), "old bulb hours", "a 60 W incandescent", "\uD83D\uDD6F\uFE0F"),
                Tile(f1(wh / TEA_CUP_WH), "cups of tea", "boiled from room temperature", "\u2615"),
                Tile(f1(wh / TOAST_SLICE_WH), "slices of toast", "a 1,200 W toaster, 2\u00bd min a slice", "\uD83C\uDF5E"),
                Tile(duration(wh / MICROWAVE_W * 3600), "of microwave", "at 1,100 W: popcorn takes about 3 min", "\uD83C\uDF7F"),
                Tile(duration(wh / HAIR_DRYER_W * 3600), "of hair dryer", "on high, about 1,800 W", "\uD83D\uDCA8"),
                Tile(duration(wh / ROUTER_W * 3600), "of Wi-Fi", "a home router uses about 6 W", "\uD83D\uDCF6"),
                Tile(f1(wh / AA_WH), "AA batteries", "alkaline, about 2.7 Wh each", "\uD83E\uDEAB"),
            ))
            eyebrowRow("Muscle power")
            addTileList(listOf(
                Tile(distance(wh * 3600 / (7 * G)), "bowling-ball lift", "how high it could lift a 7 kg ball", "\uD83C\uDFB3"),
                Tile(distance(wh * 3600 / (CAR_KG * G)), "car lift", "raising a 1,500 kg car straight up", "\uD83D\uDE99"),
                Tile(fmt(wh * 3600 / (PERSON_KG * G * FLOOR_M)), "flights of stairs", "a 75 kg person, 3 m a floor, pure lifting work", "\uD83E\uDE9C"),
                Tile(f1(wh / BANANA_WH), "bananas", "of food energy, 105 Calories each", "\uD83C\uDF4C"),
            ))
            eyebrowRow("Other gadgets")
            addTileList(listOf(
                Tile(f1(wh / LAPTOP_WH), "laptop charges", "a 50 Wh laptop battery", "\uD83D\uDCBB"),
                Tile(fmt(wh / WATCH_WH), "smartwatch charges", "about 1.6 Wh each", "\u231A"),
                Tile(fmt(wh / RING_WH), "smart ring charges", "about 0.08 Wh each", "\uD83D\uDC8D"),
                Tile(duration(wh / TV_W * 3600), "of TV", "a 55-inch LED TV at about 100 W", "\uD83D\uDCFA"),
            ))
        }
    }

    /** Where the phone's battery went: idle vs screen-on, and which apps were on screen while it dropped. */
    private fun renderDrain(prep: DrainPrep?) {
        if (prep == null) {
            section("What's draining it", "See which apps cost the most.",
                "Give Battery Lab Usage access and it will line up which apps were on screen with how fast the " +
                    "battery dropped. It sees app names and times only, never what's inside them, and it stays on this phone.").apply {
                button("Open Usage access settings", primary = true) {
                    startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            return
        }
        val now = System.currentTimeMillis()
        val recent = prep.recent
        val d = prep.d
        if (d.totalDrop < 5 || !d.baselineKnown) {
            section("What's draining it", "Collecting\u2026",
                if (d.totalDrop < 5) "Needs a few hours of the phone off the charger, within the days Android keeps app history."
                else "Needs at least an hour with the screen off to measure idle drain before anything can be split by app.")
            return
        }
        val spanDays = (now - (recent.firstOrNull()?.timeMs ?: now)) / 86_400_000.0
        section("What's draining it", "Battery lost while each app was on screen.",
            "Last ${f1(spanDays)} days of Android's app history. A correlation from 1% steps, not a per-app meter: " +
                "background work and radios count toward whatever was on screen.").apply {
            // Idle is shown as the remainder so the pair always reads exactly 100%.
            val appsPct = pctOf(d.totalDrop - d.standbyDrop, d.totalDrop)
            addTileList(listOfNotNull(
                Tile("$appsPct%", "used by apps on screen", "${f1(d.screenOnHours)} h of screen time", "\uD83D\uDCF2"),
                Tile("${100 - appsPct}%", "idle drain", "what the phone uses on its own", "\uD83D\uDCA4"),
                d.screenOnRate?.let { Tile("${f1(it)}%", "an hour, screen on",
                    if (it < 0.1) "barely any drain" else "about ${fmt(100 / it)} h of use from full", "\u2600\uFE0F") },
                // This week's screen history; the "Asleep and idle" figure is over all quiet stretches, so they can differ.
                d.screenOffRate?.let { Tile("${f1(it)}%", "an hour, screen off",
                    if (it < 0.1) "barely any drain this week" else "this week · about ${fmt(100 / it)} h asleep from full", "\uD83C\uDF19") },
            ))
            val top = d.apps.take(8)
            val most = (top.firstOrNull()?.percent ?: 0.0).takeIf { it > 0 } ?: 1.0
            if (top.isNotEmpty()) addView(label("Top apps", 12.5f, Palette.body).apply { setPadding(0, dp(14), 0, 0) })
            top.forEach { a -> addView(appRow(prep.labels[a.app] ?: a.app, a.percent / most,
                "${f1(a.percent / d.totalDrop * 100)}%", listOfNotNull("${f1(a.foregroundHours)} h",
                    a.mahUsed?.let { "${fmt(it)} mAh" }, a.activeWatts?.let { String.format(Locale.US, "%.1f W", it) },
                    a.backgroundServiceHours.takeIf { it >= 0.1 }?.let { "+${f1(it)} h bg" }).joinToString(" \u00b7 "))) }
            if (top.isNotEmpty()) addView(label("Share of all battery lost, beyond idle drain, while the app was on screen; then its screen time.",
                11.5f, Palette.quiet).apply { setPadding(0, dp(8), 0, 0) })
            val candidates = d.apps.map { it.app }.filter { it != DrainAnalysis.UNATTRIBUTED }.take(15)
            if (candidates.isNotEmpty() || (prep.hidden ?: 0) > 0)
                addView(makeButton("Hide apps from this list") { askHide(candidates) },
                    LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
            evidence("Phone readings plus Android's app history: which app was on screen, and foreground services.",
                "Drop beyond the idle rate while each app was on screen. " +
                    (if (d.usedCounter) "Measured with the charge counter (mAh), so it isn't limited to 1% steps. "
                    else "Measured in 1% steps. ") + "W = mAh \u00d7 voltage \u00f7 hours on screen.",
                "Last ${f1(spanDays)} days. Apps you hid count as Other. \u201cbg\u201d is time the app ran a visible background service.")
        }
    }

    // ============================================================================================
    // Lab cards: honesty audit, forecasts, charging, nights, planner, heat, journal, overhead,
    // capability inventory. Each ends with a "How this is worked out" inspector.
    // ============================================================================================

    private fun na(v: Double?, unit: String = "", digits: Int = 1) =
        v?.let { String.format(Locale.US, "%.${digits}f", it) + unit } ?: "unknown"

    private fun LinearLayout.auditRow(title: String, lines: List<String>) {
        addView(label(title, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(2)) })
        addView(label(lines.joinToString("\n"), 12.5f, Palette.body))
    }

    private fun renderAudit(phone: List<Reading>, phoneWidget: List<Reading>, watch: List<Reading>, ring: List<Reading>,
                            cap: CapacityEstimate, gaps: GapLimits) {
        val a = HonestyAuditor.audit(phone, phoneWidget, watch, ring, cap.combinedMah, gaps)
        section("Honesty audit", "Do the percentages match the hardware?",
            "Six checks of what Android and Samsung show against the phone's own charge counter. " +
                "“unknown” means there isn't enough data yet, not that something is wrong.").apply {
            auditRow("A · Top of charge", listOf(
                "Slow finish adds ${na(a.medianCvTaperAddedMah, " mAh", 0)} over ${na(a.medianCvTaperMinutes, " min", 0)}",
                "Shows 100% for ${na(a.topHoldMinutes, " min", 0)} after unplugging; hidden buffer ${na(a.medianTopHoldBufferMah, " mAh", 0)}"))
            auditRow("B · Does every 1% hold the same charge?", listOf(
                "mAh per 1%: top ${na(a.upperBandMahPerPct, "", 0)} · middle ${na(a.midBandMahPerPct, "", 0)} · bottom ${na(a.lowerBandMahPerPct, "", 0)}",
                "Top vs bottom: ${na(a.gaugeSkewRatio, "×", 2)} (1.00× is perfectly even)"))
            auditRow("C · Bottom cliff", listOf(
                "Below 15%: ${na(a.bottom15DrainRatePpPerHr, "%/h")} vs ${na(a.midBandDrainRatePpPerHr, "%/h")} in the middle",
                "Ratio ${na(a.bottom15CliffRatio, "×", 2)} (above 1.3× means the last 15% goes faster)"))
            auditRow("D · Voltage at rest", buildList {
                add("At 80% ${a.restingVoltage80Mv?.let { "$it mV" } ?: "unknown"} · 50% ${a.restingVoltage50Mv?.let { "$it mV" } ?: "unknown"} · 20% ${a.restingVoltage20Mv?.let { "$it mV" } ?: "unknown"}")
                add(if (a.currentUnitsPerMa == null) "Internal resistance: unknown (current units not verified yet)"
                    else "Internal resistance: ${a.internalResistanceMilliohms?.let { "$it mΩ" } ?: "unknown"}")
            })
            auditRow("E · Cycle counter", listOf(
                "Android reports ${a.reportedCycleDelta?.toString() ?: "unknown"} new cycles",
                "Charge counter says ${na(a.coulombThroughputCycles, "", 2)}; measured percent steps say ${na(a.pctPointThroughputCycles, "", 2)}"))
            auditRow("F · Samsung widget vs. direct readings", listOf(
                "Phone: ${a.phoneWidgetPairs} pairs, off by ${na(a.phoneWidgetMeanAbsErrorPp, " pts")} on average" +
                    (a.phoneWidgetMeanErrorPp?.let { " (bias ${f1(it)})" } ?: "") +
                    (a.phoneWidgetMaxAbsErrorPp?.let { ", worst $it" } ?: "") +
                    (a.phoneWidgetLagMinutesP50?.let { ", lags ${fmt(it)} min" } ?: ""),
                "Watch: ${a.watchWidgetVsCompanionPairs} pairs, off by ${na(a.watchWidgetMeanAbsErrorPp, " pts")}",
                "Ring: ${a.ringWidgetVsBlePairs} pairs, off by ${na(a.ringWidgetMeanAbsErrorPp, " pts")}"))
            evidence("Phone readings from Android's BatteryManager (percent, charge counter, voltage, current), " +
                    "the Samsung battery widget, the watch companion app and the Ring's own Bluetooth reports.",
                "A: counter gained after 99% and the 100% plateau after unplugging. B: total counter drop ÷ total percent drop in each band. " +
                    "C: %/h in each band, counting steps where the percent held as well as fell. D: median voltage at rest " +
                    "(screen off, under 250 mA, 20–30 °C); resistance = ΔV / ΔI. E: counter throughput ÷ capacity. " +
                    "F: widget vs direct readings within 2 min (phone) or 5 min (Watch, Ring).",
                "${phone.size} phone readings, ${phoneWidget.size} widget readings. Gaps longer than the polling " +
                    "allowance are skipped, and readings flagged as repeats or jumps are left out.")
        }
    }

    private fun renderForecasts(all: List<Forecast>, devices: List<Triple<String, String, Int>>) {
        val pending = all.count { it.status == "PENDING" }
        // Graded per device: a slow-draining Ring is easy to predict and would flatter the phone's
        // numbers, and Android only predicts the phone.
        val byDevice = all.groupBy { it.device }.map { (id, list) ->
            val name = devices.firstOrNull { it.first == id }?.second ?: when {
                id.startsWith("watch") -> "Watch"; id.startsWith("ring") -> "Ring"; else -> "Phone"
            }
            Triple(id, name, ForecastScoring.reportCard(list))
        }.sortedBy { if (it.first == "phone") 0 else 1 }
        val scored = byDevice.sumOf { (_, _, card) -> card.sumOf { it.n } }
        section("Prediction report card", if (scored == 0) "Grading predictions…" else "How close each estimate came.",
            if (scored == 0) "Every hour Battery Lab freezes its own time-left estimate and Android's, then checks them 2, 4 and 8 hours later. " +
                "$pending waiting to be graded." else null).apply {
            for ((_, name, card) in byDevice) {
                if (card.all { it.n == 0 && it.androidN == 0 }) continue
                addView(eyebrow(name).apply { setPadding(0, dp(12), 0, 0) })
                for (h in card) {
                    if (h.n == 0 && h.androidN == 0) continue
                    addView(label("${h.hours} hours ahead", 13f, ink, bold = true).apply { setPadding(0, dp(6), 0, dp(2)) })
                    addView(label("Battery Lab: off by ${na(h.labMae, " pts")} (bias ${na(h.labBias, "")}, n=${h.n})" +
                        (if (h.androidN > 0) "\nAndroid: off by ${na(h.androidMae, " pts")} (bias ${na(h.androidBias, "")}, n=${h.androidN})" else ""),
                        12.5f, Palette.body))
                }
            }
            evidence("Forecasts stored on this phone, frozen when made, model ${ForecastScoring.MODEL_VERSION}.",
                "Error = predicted % − actual % at each horizon, interpolated between the readings around it. " +
                    "Shown as the mean absolute error and the average signed bias (positive = too optimistic).",
                "${all.size} forecasts; $pending still pending. A charge or a gap before a horizon removes that horizon from grading.")
        }
    }

    /** "1 h 05 min" style for longer times, "42 min" for shorter. */
    private fun mins(m: Double): String {
        val total = m.roundToInt()
        return if (total < 90) "$total min" else "${total / 60} h ${String.format(Locale.US, "%02d", total % 60)} min"
    }

    private fun estimateTile(e: ChargeEstimate?, label: String, icon: String): Tile? = e?.let {
        Tile(mins(it.minutes), label, when {
            it.samples > 0 -> "measured · ${it.samples} charge${if (it.samples == 1) "" else "s"}"
            it.measured -> "added up from 10% steps"
            else -> "estimate · low end not seen yet"
        }, icon)
    }

    private fun renderChargeTimes(devices: List<Triple<String, String, Int>>, data: List<List<Reading>>, gaps: GapLimits) {
        val times = data.map { ChargeTimes.analyze(it, gaps) }
        if (times.all { it == null }) {
            section("How long to charge", "Collecting…", "Needs a charge watched while the device is on its charger.")
            return
        }
        section("How long to charge", "From empty to full, and every 10% in between.",
            "Timed from the moment each device's battery crossed one level to the moment it crossed the next.").apply {
            devices.forEachIndexed { i, (_, name, color) ->
                val t = times[i] ?: return@forEachIndexed
                addView(deviceBanner(name, color))
                addTileList(listOfNotNull(
                    estimateTile(t.zeroToFull, "0 → 100%", "🔋"),
                    estimateTile(t.twentyToEighty, "20 → 80%", "⚡"),
                    estimateTile(t.eightyToFull, "80 → 100%", "🐢"),
                    t.usualPlugIn?.takeIf { it in 1..90 }?.let { estimateTile(t.fromUsualToFull, "$it → 100%", "🔌")
                        ?.copy(label = "from your usual $it%") },
                ))
                val longest = t.steps.values.maxOrNull() ?: 1.0
                for (lo in ChargeTimes.STEPS) t.steps[lo]?.let {
                    addView(barRow("$lo→${lo + 10}%", it / longest, mins(it), color,
                        "${t.stepSamples[lo] ?: 0} charge${if (t.stepSamples[lo] == 1) "" else "s"}"))
                }
                val notes = listOfNotNull(
                    t.last?.let { (from, to, m) -> "Last charge: $from% → $to% in ${mins(m)}." },
                    if (t.fastest2080 != null && t.slowest2080 != null && t.slowest2080 > t.fastest2080)
                        "20 → 80% ranged from ${mins(t.fastest2080)} to ${mins(t.slowest2080)}." else null,
                )
                if (notes.isNotEmpty()) addView(label(notes.joinToString(" "), 12f, Palette.body).apply { setPadding(0, dp(6), 0, 0) })
            }
            evidence("Readings taken while each device showed as charging: Android's own for the phone, the charging bolt " +
                    "on Samsung's widget for the Ring and Watch.",
                "Each crossing time is interpolated between the readings either side, then the median per 10% step is taken. " +
                    "Spans like 0 → 100% are measured directly when a charge covered them, otherwise added up from the steps; " +
                    "steps below the lowest one seen borrow its time.",
                "${times.filterNotNull().sumOf { it.charges }} charges in all. Readings come every 15 minutes, so a " +
                    "fast-charging Ring may cross several steps between two readings; those steps share the time evenly.")
        }
    }

    private fun renderCharging(phone: List<Reading>, gaps: GapLimits) {
        val charges = ChargeCurves.observedCharges(phone, gaps)
        if (charges.isEmpty()) {
            section("Charging speed", "Collecting…", "Needs a charge watched from 20% or so upward.")
            return
        }
        section("Charging speed", "Minutes for each stretch of a charge.").apply {
            val byType = charges.groupBy { it.pluggedType }
            val longest = byType.values.flatMap { ChargeCurves.bandMedians(it).values }.maxOrNull() ?: 1.0
            for ((type, list) in byType.entries.sortedByDescending { it.value.size }) {
                val med = ChargeCurves.bandMedians(list)
                if (med.isEmpty()) continue
                addView(label("${if (type == null) "Charger type not recorded" else ChargeCurves.plugName(type)} · ${list.size} charge${if (list.size == 1) "" else "s"}",
                    12.5f, Palette.body).apply { setPadding(0, dp(10), 0, 0) })
                for (band in ChargeCurves.BANDS) med[band]?.let {
                    addView(barRow("${band.first}→${band.second}%", it / longest, "${fmt(it)} min", Palette.phone))
                }
            }
            ChargeCurves.topUpPercent(charges)?.let {
                addTiles(listOf(Triple("+${fmt(it)}%", "from a 15-minute top-up", "starting near 20%")))
            }
            evidence("Phone readings while Android said it was charging, with the plug type it reported.",
                "For each charge: time between first reaching each band's start and end. Median per band and plug type. " +
                    "Top-up walks the median 20→50, 50→80 and 80→100% band times in order for 15 minutes.",
                "${charges.size} observed charges. Charges with gaps over the polling allowance are skipped.")
        }
    }

    private fun renderNights(stretches: List<IdleStretch>) {
        val rep = IdleAnalysis.report(stretches)
        if (stretches.isEmpty()) {
            section("Asleep and idle", "Collecting…", "Needs at least 4 hours with the screen off and the phone unplugged.")
            return
        }
        section("Asleep and idle", rep.medianRate?.let { "Typically ${f1(it)}% an hour with the screen off, across every quiet stretch so far." } ?: "Idle drain").apply {
            val best = stretches.minByOrNull { it.rate }!!
            val worst = stretches.maxByOrNull { it.rate }!!
            addTiles(listOf(
                Triple("${f1(best.rate)}%/h", "best idle stretch", "${shortWhen(best.startMs)}, ${f1(best.hours)} h"),
                Triple("${f1(worst.rate)}%/h", "worst idle stretch", "${shortWhen(worst.startMs)}, ${f1(worst.hours)} h"),
            ))
            if (rep.unusual.isNotEmpty()) {
                addView(label("Unusual", 12.5f, Palette.body).apply { setPadding(0, dp(12), 0, 0) })
                rep.unusual.takeLast(5).forEach { s ->
                    val why = listOfNotNull(
                        s.cellularShare?.takeIf { it >= 0.5 }?.let { "mostly on cellular" },
                        s.aodShare?.takeIf { it >= 0.5 }?.let { "Always-On Display" },
                        s.avgTempC?.takeIf { it >= 35 }?.let { "warm (${f1(it)}°C)" })
                    addView(label("${shortWhen(s.startMs)} · ${f1(s.hours)} h · ${f1(s.rate)}%/h" +
                        (if (why.isEmpty()) "" else " — ${why.joinToString(", ")}"), 12.5f, ink))
                }
            } else if (stretches.size < 5) addView(label("Spotting unusual nights needs 5 stretches; ${stretches.size} so far.",
                11.5f, Palette.quiet).apply { setPadding(0, dp(8), 0, 0) })
            evidence("Phone readings plus screen state from Android's app history (or recorded display state).",
                "Stretches of 4 h or more unplugged with the screen on at most 5% of the time. Rate = drop ÷ hours. " +
                    "Unusual = above the median + 3 × median absolute deviation and at least 0.5%/h above typical.",
                "${stretches.size} stretches. Each stretch ends at the last reading that showed a change, so repeated cached values can't flatten it.")
        }
    }

    private var targetHours = 12
    private val reservePct = 15

    private fun renderPlanner(devices: List<Triple<String, String, Int>>, data: List<List<Reading>>, gaps: GapLimits) {
        val card = section("Will it last?", null, "Keeps a $reservePct% reserve. Each device is judged on its own history.")
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Rates don't depend on the chosen window, so work them out once, not on every chip tap.
        class PlanInput(val name: String, val percent: Int, val rates: List<Double>, val chargeRate: Double?)
        val now = System.currentTimeMillis()
        val inputs = devices.zip(data).mapNotNull { (d, readings) ->
            val last = readings.lastOrNull()?.takeIf { now - it.timeMs < 6 * 3_600_000L } ?: return@mapNotNull null
            val rates = BatteryMath.dischargeStretches(readings.map(BatteryPoint::of), gaps = gaps).map { it.drop / it.hours }
            val chargeRate = BatteryMath.median(ChargeCurves.observedCharges(readings, gaps)
                .filter { it.peakMs - it.startMs >= 10 * 60_000L }
                .map { (it.toPct - it.fromPct) / ((it.peakMs - it.startMs) / 60_000.0) })
            PlanInput(d.second, last.percent, rates, chargeRate)
        }
        fun fill() {
            holder.removeAllViews()
            holder.addView(chipRow(*listOf(8, 12, 18, 24).map { h ->
                chip("$h h", if (h == targetHours) Palette.navy else Palette.track).apply {
                    setOnClickListener { targetHours = h; fill() }
                }
            }.toTypedArray()))
            val assessed = inputs.map {
                ReadinessPlanner.assess(it.name, it.percent, it.rates, it.chargeRate, targetHours.toDouble(), reservePct)
            }
            if (assessed.isEmpty()) holder.addView(label("No recent readings to plan with.", 12.5f, Palette.quiet))
            for (r in assessed) {
                val verdict = when (r.meetsTarget) {
                    null -> "not enough history yet"
                    true -> "lasts about ${fmt(r.hoursToReserve!!)} h" + (if (fmt(r.hoursToReserveFast!!) == fmt(r.hoursToReserveSlow!!)) ""
                        else " (${fmt(r.hoursToReserveFast)}–${fmt(r.hoursToReserveSlow)} h)") + " ✓"
                    false -> "falls short after about ${fmt(r.hoursToReserve!!)} h" + when {
                        // A top-up can't fix it if even a full charge runs out first.
                        r.hoursFromFull != null && r.hoursFromFull < targetHours ->
                            "; even a full charge lasts only about ${fmt(r.hoursFromFull)} h"
                        else -> r.topUpMinutes?.let { "; charge ${fmt(it.coerceAtLeast(1.0))} min first" } ?: ""
                    }
                }
                holder.addView(label("${r.name} at ${r.percent}% — $verdict", 13f, ink).apply { setPadding(0, dp(8), 0, 0) })
            }
            val order = ReadinessPlanner.order(assessed)
            if (order.size > 1) holder.addView(label("One charger? Charge ${order.joinToString(", then ") { it.name }}.",
                12.5f, Palette.tintInk).apply { setPadding(0, dp(10), 0, 0) })
        }
        fill()
        card.addView(holder)
        card.evidence("Latest reading of each device (within 6 hours) and its past discharge stretches and charges.",
            "Hours = (percent − reserve) ÷ drain rate, using the median rate, with the 75th/25th percentile rates as the range. " +
                "Top-up minutes = shortfall ÷ median charging speed.",
            "Discharge stretches of at least 1 h and a 4-point drop. Devices are never averaged together.")
    }

    private fun renderHeat(phone: List<Reading>, gaps: GapLimits) {
        val m = HeatExposure.matrix(phone, gaps)
        val total = m.sumOf { it.sum() }
        if (total < 1) return
        val most = m.maxOf { it.maxOrNull() ?: 0.0 }.coerceAtLeast(0.01)
        section("Heat and charge level", "Hours spent at each temperature and charge.",
            "Heat while nearly full ages a battery fastest, so the bottom-right box is the one to keep small.").apply {
            fun cell(text: String, alpha: Int?, bold: Boolean = false) = TextView(this@StatsActivity).apply {
                this.text = text; textSize = 12f; gravity = Gravity.CENTER; setTextColor(ink)
                if (bold) typeface = medium
                setPadding(dp(2), dp(8), dp(2), dp(8))
                if (alpha != null) background = rounded(Color.argb(alpha, 233, 162, 59), 8)
            }
            fun row(vararg views: View) = LinearLayout(this@StatsActivity).apply {
                views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
            }
            addView(row(cell("", null), *HeatExposure.CHARGE_BANDS.map { cell(it, null, true) }.toTypedArray()))
            HeatExposure.TEMP_BANDS.forEachIndexed { t, name ->
                addView(row(cell(name, null, true), *m[t].map { h ->
                    cell(if (h <= 0) "–" else if (h < 0.1) "<0.1 h" else "${f1(h)} h", (25 + 200 * h / most).toInt())
                }.toTypedArray()))
            }
            evidence("Phone temperature and percent from Android's BatteryManager.",
                "Each step between two readings adds its duration to the box of the earlier reading.",
                "${f1(total)} hours with a temperature reading; gaps longer than the polling allowance are skipped.")
        }
    }

    private val journalCategories = listOf(
        "charger" to "New charger or cable", "aod" to "Always-On Display changed", "watch_face" to "Watch face changed",
        "battery_saver" to "Battery saver changed", "ring_firmware" to "Ring update", "note" to "Just a note")

    private fun renderJournal(stretches: List<IdleStretch>) {
        val entries = store.journal(40)
        section("Change journal", "What changed, and did it matter?",
            "Battery Lab notes software updates by itself. Add your own changes so their effect on idle drain can be checked.").apply {
            if (entries.isEmpty()) addView(label("Nothing yet.", 12.5f, Palette.quiet).apply { setPadding(0, dp(8), 0, 0) })
            entries.take(12).forEach { e ->
                val verdict = if (e.category == "note") null
                else if (!UpdateImpactAnalysis.measures(e)) "Effect not measured: only the phone's idle drain is analysed so far."
                else UpdateImpactAnalysis.assess(e, stretches).let { im ->
                    val d = im.delta
                    when {
                        im.strength == null || d == null -> "Needs 3 idle stretches before and after (${im.before.size} / ${im.after.size})."
                        else -> "${im.strength}: idle drain ${if (d.estimate >= 0) "+" else ""}${f1(d.estimate)}%/h " +
                            "(${f1(d.low)} to ${f1(d.high)})" + (im.confounder?.let { ". $it" } ?: "")
                    }
                }
                addView(label("${shortWhen(e.timeMs)} · ${e.title}", 13f, ink).apply {
                    setPadding(0, dp(10), 0, 0)
                    setOnLongClickListener {
                        android.app.AlertDialog.Builder(this@StatsActivity).setMessage("Delete this entry?")
                            .setPositiveButton("Delete") { _, _ -> store.deleteJournal(e.id); render() }
                            .setNegativeButton("Cancel", null).show()
                        true
                    }
                })
                verdict?.let { addView(label(it, 11.5f, Palette.body)) }
            }
            addView(makeButton("Add a change or note") { askJournal() },
                LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
            evidence("Entries saved on this phone: detected OS and app updates, and the ones you add.",
                "Idle drain in screen-off stretches before vs after the change (Welch interval). OS updates skip the first " +
                    "${UpdateImpactAnalysis.SETTLE_HOURS} h while the phone re-indexes. Cellular or Always-On Display shifts are flagged.",
                "Up to 14 stretches each side. 3+ per side is preliminary, 7+ established. Long-press an entry to delete it.")
        }
    }

    private fun askJournal() {
        android.app.AlertDialog.Builder(this).setTitle("What changed?")
            .setItems(journalCategories.map { it.second }.toTypedArray()) { _, which ->
                val (category, name) = journalCategories[which]
                val input = android.widget.EditText(this).apply { setText(name); setSelection(name.length) }
                android.app.AlertDialog.Builder(this).setTitle("Describe it").setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val device = when (category) { "ring_firmware" -> BatteryStore.RING_ID; "watch_face" -> "watch"; else -> "phone" }
                        store.addJournal(device, category, input.text.toString().ifBlank { name })
                        render()
                    }.setNegativeButton("Cancel", null).show()
            }.show()
    }

    private fun renderOverhead() {
        val s = Overhead.summarize(store.probeEvents(Overhead.DEVICE, System.currentTimeMillis() - 86_400_000L))
        if (s.polls == 0) return
        section("What Battery Lab costs", "Its own footprint over the last day.").apply {
            addTiles(listOfNotNull(
                Triple("${s.polls}", if (s.polls == 1) "check" else "checks", s.meanPollMs?.let { "about ${fmt(it)} ms each" }),
                Triple(String.format(Locale.US, "%.1f s", s.cpuSeconds), "CPU time", "all checks together"),
                Triple("${s.bleWakeups}", "Ring wake-ups", "direct Bluetooth reads"),
                s.dbBytes?.let { Triple(String.format(Locale.US, "%.1f MB", it / 1_048_576.0), "database", "on this phone") },
            ))
            evidence("Battery Lab's own log of each background check.",
                "Wall time and process CPU time measured around each check; Ring reads counted when a Bluetooth read runs.",
                "Last 24 hours. Screen time spent looking at the app isn't included.")
        }
    }

    private fun renderCapabilities() {
        val rows = store.capabilities()
        if (rows.isEmpty()) return
        val verified = rows.count { it.status == CapabilityProbe.VERIFIED }
        section("What each device can report", "$verified of ${rows.size} fields recorded.").apply {
            for ((kind, list) in rows.groupBy { it.deviceKind }) {
                addView(label(kind.replaceFirstChar { it.uppercase() }, 12.5f, Palette.body, bold = true).apply { setPadding(0, dp(10), 0, dp(2)) })
                list.forEach { r ->
                    val status = when (r.status) {
                        CapabilityProbe.VERIFIED -> "recorded"; CapabilityProbe.DISABLED -> "available, off"
                        CapabilityProbe.PERMISSION -> "needs permission"; CapabilityProbe.UNSUPPORTED -> "not available"
                        CapabilityProbe.UNKNOWN_AGE -> "age unknown"; else -> r.status.lowercase()
                    }
                    addView(label("${r.fieldKey} — $status" + (r.observedMinStep?.let { " · step $it" } ?: ""), 12f, ink))
                }
            }
            addView(makeButton("Share this list (no IDs or names)") { shareCapabilities(rows) },
                LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
            evidence("Checked once a day from the last 14 days of readings.",
                "A field is “recorded” when non-empty values were actually stored; the step is the smallest change seen.",
                "Firmware ${CapabilityProbe.firmware()}. The shared version leaves out sample values, addresses and serials.")
        }
    }

    private fun shareCapabilities(rows: List<CapabilityRow>) {
        val json = CapabilityProbe.redactedJson(rows)
        android.app.AlertDialog.Builder(this).setTitle("Share capability list?")
            .setMessage("This sends a list of which battery fields work on this phone, with no serials, addresses or " +
                "device names, to the app you pick next.")
            .setPositiveButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, json)
                }, "Share capability list"))
            }.setNegativeButton("Cancel", null).show()
    }

    /** One line per app: name, bar, then "8.2%" with the screen time in small grey after it. */
    private fun appRow(name: String, fraction: Double, value: String, hours: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        addView(TextView(this@StatsActivity).apply {
            text = name; textSize = 13f; setTextColor(ink); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(dp(104), -2))
        val fill = fraction.coerceIn(0.03, 1.0).toFloat()
        addView(LinearLayout(this@StatsActivity).apply {
            background = rounded(Palette.track, 4)
            addView(View(this@StatsActivity).apply { background = rounded(Palette.phone, 4) }, LinearLayout.LayoutParams(0, -1, fill))
            addView(View(this@StatsActivity), LinearLayout.LayoutParams(0, -1, 1f - fill))
        }, LinearLayout.LayoutParams(0, dp(10), 1f))
        addView(TextView(this@StatsActivity).apply {
            val s = android.text.SpannableStringBuilder(value)
            s.setSpan(android.text.style.ForegroundColorSpan(ink), 0, value.length, 0)
            s.setSpan(android.text.style.TypefaceSpan(medium), 0, value.length, 0)
            val at = s.length
            s.append("  ").append(hours)
            s.setSpan(android.text.style.AbsoluteSizeSpan(11, true), at, s.length, 0)
            text = s; textSize = 13.5f; setTextColor(Palette.quiet); gravity = Gravity.END; maxLines = 1
        }, LinearLayout.LayoutParams(dp(92), -2))
    }

    /** A small heading inside a card, to group its tiles. */
    private fun LinearLayout.eyebrowRow(text: String) =
        addView(eyebrow(text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

    private companion object {
        const val TAG = "BatteryLabStats"
        val SECTION_ICONS = mapOf(
            "Phone battery capacity" to "\uD83D\uDD0B", "By the numbers" to "\uD83D\uDCCA",
            "Charging habits" to "\uD83D\uDD0C", "Around the clock" to "\uD83D\uDD70\uFE0F",
            "Showdown" to "\uD83C\uDFC6", "Head to head" to "\uD83E\uDD4A", "Over time" to "\uD83D\uDCC8", "Data coverage" to "\uD83D\uDCE1", "Drain spread" to "\uD83D\uDCC9", "Weekdays vs weekends" to "\uD83D\uDCC5",
            "Temperature" to "\uD83C\uDF21\uFE0F", "What it costs" to "\uD83D\uDCB5",
            "Where it comes from" to "\uD83C\uDFED", "Just for fun" to "\uD83C\uDF89",
            "What's draining it" to "\uD83D\uDD0D", "Honesty audit" to "\uD83E\uDDD0",
            "Prediction report card" to "\uD83D\uDCDD", "Charging speed" to "\u26A1", "How long to charge" to "\u23F3",
            "Asleep and idle" to "\uD83D\uDE34", "Will it last?" to "\uD83E\uDDED",
            "Heat and charge level" to "\uD83D\uDD25", "Change journal" to "\uD83D\uDCD3",
            "What Battery Lab costs" to "\uD83E\uDDEA", "What each device can report" to "\uD83D\uDCE1",
        )
        // Household and body comparisons (rough, typical values; captions say which).
        const val G = 9.81
        const val LED_BULB_W = 9.0
        const val OLD_BULB_W = 60.0
        const val TEA_CUP_WH = 23.0          // 250 ml from 20 to 100 C
        const val TOAST_SLICE_WH = 50.0      // 1,200 W for 2.5 min
        const val MICROWAVE_W = 1100.0
        const val HAIR_DRYER_W = 1800.0
        const val ROUTER_W = 6.0
        const val TV_W = 100.0
        const val AA_WH = 2.7
        const val CAR_KG = 1500.0
        const val PERSON_KG = 75.0
        const val FLOOR_M = 3.0
        const val BANANA_WH = 122.0          // 105 kcal
        const val LAPTOP_WH = 50.0
        const val WATCH_WH = 1.6             // ~425 mAh at 3.85 V
        const val RING_WH = 0.08             // ~21 mAh at 3.85 V
        /** Shown until the user sets their own rate; their rate lives only in the phone's settings. */
        const val DEFAULT_CENTS_PER_KWH = 18.0
        const val KEY_CENTS_PER_KWH = "cents_per_kwh"
        const val FRIDGE_KWH_PER_DAY = 1.5
        const val EV_WH_PER_METER = 300.0 / 1609.0
        // Per Wh of electricity, rough US figures (EIA): coal ~1.1 lb/kWh, gas ~7.4 ft3/kWh,
        // grid CO2 ~0.81 lb/kWh. Uranium: ~45 GWd/t burnup at ~33% plant efficiency.
        const val COAL_G_PER_WH = 0.5
        const val GAS_L_PER_WH = 0.21
        const val URANIUM_G_PER_WH = 2.8e-6
        const val CO2_G_PER_WH = 0.37
        const val SOLAR_PANEL_W = 400.0
        const val WIND_TURBINE_W = 3_000_000.0
        const val CYCLIST_W = 100.0
    }

    /** Grams with a unit that keeps the number readable: kg, g, mg or \u00b5g. */
    private fun mass(g: Double) = when {
        g >= 1000 -> "${f1(g / 1000)} kg"
        g >= 10 -> "${fmt(g)} g"
        g >= 1 -> "${f1(g)} g"
        g >= 0.001 -> "${fmt(g * 1000)} mg"
        else -> "${fmt(g * 1e6)} \u00b5g"
    }

    private fun volume(l: Double) = if (l >= 1) "${f1(l)} L" else "${fmt(l * 1000)} mL"

    private fun duration(sec: Double) = when {
        sec >= 3600 -> "${f1(sec / 3600)} h"
        sec >= 60 -> "${fmt(sec / 60)} min"
        sec >= 1 -> "${fmt(sec)} s"
        else -> "${String.format(Locale.US, "%.2f", sec)} s"
    }

    private fun rateIsSet() = PollScheduler.prefs(this).contains(KEY_CENTS_PER_KWH)
    private fun centsPerKwh() = PollScheduler.prefs(this).getFloat(KEY_CENTS_PER_KWH, DEFAULT_CENTS_PER_KWH.toFloat()).toDouble()

    /** Asks for the all-in price per kWh (total bill \u00f7 kWh used); stored on the phone only. */
    private fun askRate() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (rateIsSet()) f1(centsPerKwh()) else "")
            hint = "for example ${f1(DEFAULT_CENTS_PER_KWH)}"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Your electricity rate")
            .setMessage("Cents per kWh, all charges included: your bill's total divided by the kWh used.")
            .setView(LinearLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (v != null && v in 1.0..200.0) {
                    PollScheduler.prefs(this).edit().putFloat(KEY_CENTS_PER_KWH, v.toFloat()).apply()
                    render()
                } else android.widget.Toast.makeText(this, "Enter cents per kWh between 1 and 200", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Use default") { _, _ -> PollScheduler.prefs(this).edit().remove(KEY_CENTS_PER_KWH).apply(); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Pick apps to count as "Other". Already-hidden apps are never listed by name. */
    private fun askHide(candidates: List<String>) {
        val labels = candidates.map { UsageSource.label(this, it) }.toTypedArray()
        val picked = BooleanArray(candidates.size)
        val hidden = UsageSource.hiddenCount(this) ?: 0
        android.app.AlertDialog.Builder(this)
            .setTitle("Count as \u201cOther\u201d")
            .setMultiChoiceItems(labels, picked) { _, i, on -> picked[i] = on }
            .setPositiveButton("Hide") { _, _ ->
                UsageSource.hideApps(this, candidates.filterIndexed { i, _ -> picked[i] })
                render()
            }
            .apply { if (hidden > 0) setNeutralButton("Unhide all ($hidden)") { _, _ -> UsageSource.unhideAll(this@StatsActivity); render() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Cents under a dollar ("0.48¢"), dollars above ("$1.73"). */
    private fun money(cents: Double) =
        if (cents < 100) String.format(Locale.US, "%.2f¢", cents) else String.format(Locale.US, "$%.2f", cents / 100)

    private fun fmt(v: Double) = String.format(Locale.getDefault(), "%,d", v.roundToInt())
    private fun pctOf(part: Double, whole: Double) = if (whole <= 0) 0 else (part / whole * 100).roundToInt()
    /** Weekday and time for the past week; older events get their date, or "Tue 3:15 PM" could be months ago. */
    private fun shortWhen(ms: Long) = java.text.SimpleDateFormat(
        if (System.currentTimeMillis() - ms > 6 * 86_400_000L) "MMM d, h:mm a" else "EEE h:mm a", Locale.getDefault()).format(Date(ms))
    private fun distance(m: Double) = if (m >= 1000) "${f1(m / 1000)} km" else "${fmt(m)} m"
    private fun hoursOrMinutes(h: Double) = if (h < 1.5) "${fmt(h * 60)} min" else durationHours(h)
    private fun hourName(h: Int) = when (h) { 0 -> "midnight"; 12 -> "noon"; in 1..11 -> "$h AM"; else -> "${h - 12} PM" }
}

/** Factory capacities for phones Battery Lab knows; typical is the advertised figure. */
data class PhoneSpec(val typicalMah: Double, val ratedMah: Double)

object PhoneSpecs {
    fun forModel(model: String): PhoneSpec? = when {
        model.startsWith("SM-S948") -> PhoneSpec(5000.0, 4855.0)   // Galaxy S26 Ultra
        model.startsWith("SM-S938") -> PhoneSpec(5000.0, 4855.0)   // Galaxy S25 Ultra
        model.startsWith("SM-S928") -> PhoneSpec(5000.0, 4855.0)   // Galaxy S24 Ultra
        else -> null
    }
}

/** 24 small bars, one per hour of the day; missing hours are left as faint stubs. */
class HourBars(context: Context, private val values: List<Double?>, private val barColor: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val labelH = 14 * d
        val top = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val slot = width / 24f
        val chartH = height - labelH
        values.forEachIndexed { i, v ->
            val x = i * slot + slot * 0.15f
            val w = slot * 0.7f
            val h = if (v == null) 2 * d else maxOf(2 * d, (chartH * (v / top)).toFloat())
            p.color = if (v == null) Palette.track else barColor
            canvas.drawRoundRect(x, chartH - h, x + w, chartH, 2 * d, 2 * d, p)
        }
        p.color = Palette.quiet
        p.textSize = 9.5f * d
        p.textAlign = Paint.Align.CENTER
        for ((i, t) in listOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p")) canvas.drawText(t, i * slot + slot / 2, height - 2 * d, p)
    }
}
