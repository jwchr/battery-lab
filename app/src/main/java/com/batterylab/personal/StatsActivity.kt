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

    private fun render() {
        body.removeAllViews()
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

        val ringId = PollScheduler.ringDeviceId(this)
        val watchId = store.deviceIds().filter { it.startsWith("watch:") }.maxByOrNull { store.latest(it)?.timeMs ?: 0L }
        val devices = listOf(Triple("phone", "Phone", Palette.phone),
            Triple(watchId ?: "watch:unknown", "Galaxy Watch8", Palette.watch), Triple(ringId, "Galaxy Ring", Palette.ring))
        val hourOf = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY) }
        val data = devices.map { (id, _, _) -> BatteryStats.fullHoldAsCharging(store.since(id, 0L)) }
        val gaps = PollScheduler.gapLimits(this)
        val stats = data.map { BatteryStats.device(it, hourOf, gaps) }
        val phone = data[0]

        val capacity = HonestyAuditor.estimatePhoneCapacity(phone, gaps)
        val now = System.currentTimeMillis()
        val usage = if (UsageSource.granted(this)) UsageSource.load(this, now - 7 * 86_400_000L, now) else null
        val idle = IdleAnalysis.stretches(phone, usage?.screenOn, gaps = gaps)

        renderPhoneHealth(phone, capacity)
        renderPlanner(devices, data, gaps)
        renderDrain(phone, gaps, usage, capacity.combinedMah)
        renderNights(idle)
        renderCharging(phone, gaps)
        renderAudit(phone, store.since(SamsungWidgetSource.PHONE_WIDGET_DEVICE, 0L), data[1], data[2], capacity, gaps)
        renderForecasts()
        renderJournal(idle)
        renderHeat(phone, gaps)
        renderNumbers(devices, stats)
        renderHabits(stats[0])
        renderHours(devices, stats)
        renderShowdown(devices, data)
        renderRolling(devices, data)
        BatteryStats.temperature(phone)?.let(::renderTemperature)
        renderFun(stats[0], capacity.combinedMah ?: phoneCapacity(phone)?.first, phone)
        renderOverhead()
        renderCapabilities()
        body.addView(label("All numbers come from readings stored on this phone. Estimates, not lab measurements.",
            11.5f, Palette.body).apply { setPadding(dp(6), dp(4), dp(6), 0) })
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
        val capacity = lab.combinedMah ?: estimate?.first
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
                    if (pct != null) append("Roughly ${String.format(Locale.US, "%.0f", pct)}% of the ${fmt(spec!!.typicalMah)} mAh advertised")
                    else if (spec != null) append("Advertised: ${fmt(spec.typicalMah)} mAh")
                    if (spec != null) append(" (${fmt(spec.ratedMah)} mAh rated minimum).")
                    if (estimate != null) append("\nEstimated from Android's charge counter, ${shortWhen(estimate.second)}. " +
                        "A rough guide, not a measured health test.")
                }, 13f, Palette.body).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            val tiles = mutableListOf<Triple<String, String, String?>>()
            if (cycles != null) tiles += Triple("$cycles", "charge cycles", "as counted by Android")
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
                "Two methods: the counter at settled 100% plateaus, and the slope of counter vs percent over each discharge " +
                    "(15\u201398%) \u00d7 100. The headline is the median of all samples from both.",
                "${lab.sampleCount} samples. Plateaus need the charger to have finished; slopes need a 30-point stretch with 6+ readings.")
            if (spec == null) addView(label("Comparing to new needs this model's factory capacity, which Battery Lab doesn't know yet.", 11.5f, Palette.quiet))
        }
    }

    private fun renderNumbers(devices: List<Triple<String, String, Int>>, stats: List<DeviceStats>) {
        val first = stats.mapNotNull { it.firstMs }.minOrNull()
        val days = first?.let { (System.currentTimeMillis() - it) / 86_400_000.0 } ?: 0.0
        section("By the numbers", "Since Battery Lab started watching.").apply {
            addTiles(listOf(
                Triple(if (days < 2) "${(days * 24).roundToInt()} h" else "${f1(days)} days", "tracked", null),
                Triple(fmt(stats.sumOf { it.readings }.toDouble()), "readings saved", "about ${fmt(stats.sumOf { it.readings } / maxOf(days, 1.0 / 24))} a day"),
            ))
            devices.forEachIndexed { i, (_, name, color) ->
                val s = stats[i]
                if (s.readings == 0) return@forEachIndexed
                addView(deviceBanner(name, color))
                addTiles(listOfNotNull(
                    Triple(f1(s.fullBatteriesUsed), "full batteries used", "${s.percentUsed} points in all"),
                    Triple("${s.charges.size}", "charges spotted", null),
                    s.lowest?.let { Triple("${it.first}%", "lowest seen", "closest call · ${shortWhen(it.second)}") },
                    Triple("${f1(s.longestOnBatteryHours)} h", "longest unplugged", null),
                ))
            }
        }
    }

    private fun renderHabits(phone: DeviceStats) {
        if (phone.charges.isEmpty()) return
        section("Charging habits", "How you charge your phone.").apply {
            val fast = phone.fastestCharge
            addTiles(listOfNotNull(
                phone.averagePlugIn?.let { Triple("${it.roundToInt()}%", "average plug-in", "where you usually reach for the charger") },
                phone.averageUnplug?.let { Triple("${it.roundToInt()}%", "average unplug", null) },
                fast?.let { Triple("${(60 / it.ratePerMinute!!).roundToInt()} s", "per 1% at best", "fastest charge · ${shortWhen(it.startMs)}") },
                Triple("${f1(phone.hoursAt100)} h", "parked at 100%", "time plugged in and already full"),
                Triple("${f1(phone.hoursBelow20)} h", "spent under 20%", "living dangerously"),
                Triple("${pctOf(phone.hoursCharging, phone.hoursCharging + phone.hoursOnBattery)}%", "of time charging", null),
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
                if (hungry != null && calm != null) addView(label("Hungriest at ${hourName(hungry)} (${f1(s.drainByHour[hungry]!!)}%/h) · " +
                    "calmest at ${hourName(calm)} (${f1(s.drainByHour[calm]!!)}%/h)", 12f, Palette.quiet))
            }
        }
    }

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
                    val inWindow = stretches.filter { it.startMs >= now - span }
                    if (inWindow.isEmpty()) null else Triple(labelText, 100.0 * inWindow.sumOf { it.hours } / inWindow.sumOf { it.drop },
                        if (now - first < span && span < Long.MAX_VALUE / 2) "only ${f1((now - first) / day.toDouble())} days so far" else "${inWindow.size} observed stretch${if (inWindow.size == 1) "" else "es"}")
                }
                if (rows.isEmpty()) return@forEachIndexed
                addView(deviceBanner(name, color))
                val top = rows.maxOf { it.second }
                rows.forEach { (w, hours, cap) -> addView(barRow(w, hours / top, "${hours.roundToInt()} h", color, cap)) }
            }
        }
    }

    private fun renderTemperature(t: TempStats) {
        section("Temperature", "How warm your phone runs.").apply {
            addTiles(listOfNotNull(
                Triple("${f1(t.avgC)}°C", "average", "${f1(t.avgC * 9 / 5 + 32)}°F"),
                Triple("${f1(t.maxC)}°C", "hottest", shortWhen(t.maxAtMs)),
                Triple("${f1(t.minC)}°C", "coolest", null),
                t.avgChargingC?.let { c -> Triple("${f1(c)}°C", "while charging",
                    t.avgOnBatteryC?.let { "${f1(c - it)}° warmer than unplugged" }) },
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
                "At ${f1(rate)}\u00a2 per kWh, a rough US average. Set your own rate from your bill.").apply {
            addTiles(listOfNotNull(
                Triple(money(fullCharge / 1000 * rate), "per full charge", "${f1(fullCharge)} Wh"),
                if (days >= 1.0) Triple(money(cents / days * 365), "a year at this pace", "for the phone") else null,
                Triple("${fmt(wh / 1000 / FRIDGE_KWH_PER_DAY * 24 * 60)} min", "of a fridge", "a typical fridge uses about 1.5 kWh a day"),
                Triple("${fmt(wh / EV_WH_PER_METER)} m", "in an electric car", "at about 300 Wh per mile"),
            ))
            addView(makeButton(if (rateIsSet()) "Change your rate" else "Set your rate") { askRate() },
                LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        }
        section("Where it comes from", "What it takes to make ${f1(wh)} Wh.",
            "If all of it came from one source. Rough US averages, before charger losses.").apply {
            addTiles(listOf(
                Triple(mass(wh * COAL_G_PER_WH), "of coal", "burned in a power plant"),
                Triple(volume(wh * GAS_L_PER_WH), "of natural gas", "at a gas power plant"),
                Triple(mass(wh * URANIUM_G_PER_WH), "of uranium", "nuclear fuel; a grain of salt is about 60 \u00b5g"),
                Triple(duration(wh / SOLAR_PANEL_W * 3600), "of full sun", "on one 400 W rooftop panel"),
                Triple(duration(wh / WIND_TURBINE_W * 3600), "of a wind turbine", "a 3 MW turbine at full speed"),
                Triple(duration(wh / CYCLIST_W * 3600), "of pedaling", "a fit cyclist making 100 W"),
                Triple(mass(wh * CO2_G_PER_WH), "of CO\u2082", "at the US grid average"),
            ))
        }
        section("Just for fun", "Your phone has used ${f1(wh)} Wh.",
            "Energy that went through the battery since tracking started, at a nominal 3.87 V.").apply {
            addTiles(listOf(
                Triple(f1(wh / 23.0), "cups of tea", "boiled from room temperature"),
                Triple("${fmt(wh * 3600 / (7 * 9.81))} m", "bowling-ball lift", "how high it could lift a 7 kg ball"),
                Triple(f1(wh / 9.0), "LED bulb hours", "a 9 W bulb"),
                Triple(f1(wh / 2.7), "AA batteries", "alkaline, about 2.7 Wh each"),
            ))
        }
    }

    /** Where the phone's battery went: idle vs screen-on, and which apps were on screen while it dropped. */
    private fun renderDrain(phone: List<Reading>, gaps: GapLimits, usage: UsageWindow?, capacityMah: Double?) {
        if (usage == null) {
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
        val start = usage.coverageStartMs
        val recent = if (start == null) emptyList() else phone.filter { it.timeMs >= start }
        val d = DrainAnalysis.analyze(recent.map(BatteryPoint::of), usage.screenOn, usage.apps, gaps.stepMs,
            capacityMah, usage.services)
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
            addTiles(listOfNotNull(
                Triple("${pctOf(d.totalDrop - d.standbyDrop, d.totalDrop)}%", "used by apps on screen", "${f1(d.screenOnHours)} h of screen time"),
                Triple("${pctOf(d.standbyDrop, d.totalDrop)}%", "idle drain", "what the phone uses on its own"),
                d.screenOnRate?.let { Triple("${f1(it)}%", "an hour, screen on", "about ${fmt(100 / it.coerceAtLeast(0.1))} h of use from full") },
                d.screenOffRate?.let { Triple("${f1(it)}%", "an hour, screen off", "about ${fmt(100 / it.coerceAtLeast(0.1))} h asleep from full") },
            ))
            val top = d.apps.take(8)
            val most = top.firstOrNull()?.percent ?: 1.0
            if (top.isNotEmpty()) addView(label("Top apps", 12.5f, Palette.body).apply { setPadding(0, dp(14), 0, 0) })
            top.forEach { a -> addView(appRow(UsageSource.label(this@StatsActivity, a.app), a.percent / most,
                "${f1(a.percent / d.totalDrop * 100)}%", listOfNotNull("${f1(a.foregroundHours)} h",
                    a.mahUsed?.let { "${fmt(it)} mAh" }, a.activeWatts?.let { String.format(Locale.US, "%.1f W", it) },
                    a.backgroundServiceHours.takeIf { it >= 0.1 }?.let { "+${f1(it)} h bg" }).joinToString(" \u00b7 "))) }
            if (top.isNotEmpty()) addView(label("Share of all battery lost, beyond idle drain, while the app was on screen; then its screen time.",
                11.5f, Palette.quiet).apply { setPadding(0, dp(8), 0, 0) })
            val candidates = d.apps.map { it.app }.filter { it != DrainAnalysis.UNATTRIBUTED }.take(15)
            if (candidates.isNotEmpty() || (UsageSource.hiddenCount(this@StatsActivity) ?: 0) > 0)
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
                "Charge counter says ${na(a.coulombThroughputCycles, "", 2)}; percent steps say ${na(a.pctPointThroughputCycles, "", 2)}"))
            auditRow("F · Samsung widget vs. direct readings", listOf(
                "Phone: ${a.phoneWidgetPairs} pairs, off by ${na(a.phoneWidgetMeanErrorPp, " pts")} on average" +
                    (a.phoneWidgetMaxAbsErrorPp?.let { ", worst $it" } ?: "") +
                    (a.phoneWidgetLagMinutesP50?.let { ", lags ${fmt(it)} min" } ?: ""),
                "Watch: ${a.watchWidgetVsCompanionPairs} pairs, ${na(a.watchWidgetMeanErrorPp, " pts")}",
                "Ring: ${a.ringWidgetVsBlePairs} pairs, ${na(a.ringWidgetMeanErrorPp, " pts")}"))
            evidence("Phone readings from Android's BatteryManager (percent, charge counter, voltage, current), " +
                    "the Samsung battery widget, the watch companion app and the Ring's own Bluetooth reports.",
                "A: counter gained after 99% and the 100% plateau after unplugging. B/C: least-squares fits of counter vs percent " +
                    "by band, and %/h by band. D: voltage after 30 min with little current; resistance = ΔV / ΔI. " +
                    "E: counter throughput ÷ capacity. F: widget vs direct readings within 10 min.",
                "${phone.size} phone readings, ${phoneWidget.size} widget readings. Gaps longer than the polling " +
                    "allowance are skipped, and readings flagged as repeats or jumps are left out.")
        }
    }

    private fun renderForecasts() {
        val all = store.forecasts()
        val card = ForecastScoring.reportCard(all)
        val pending = all.count { it.status == "PENDING" }
        val scored = card.sumOf { it.n }
        section("Prediction report card", if (scored == 0) "Grading predictions…" else "How close each estimate came.",
            if (scored == 0) "Every hour Battery Lab freezes its own time-left estimate and Android's, then checks them 2, 4 and 8 hours later. " +
                "$pending waiting to be graded." else null).apply {
            for (h in card) {
                if (h.n == 0 && h.androidN == 0) continue
                addView(label("${h.hours} hours ahead", 13f, ink, bold = true).apply { setPadding(0, dp(10), 0, dp(2)) })
                addView(label("Battery Lab: off by ${na(h.labMae, " pts")} (bias ${na(h.labBias, "")}, n=${h.n})\n" +
                    "Android: off by ${na(h.androidMae, " pts")} (bias ${na(h.androidBias, "")}, n=${h.androidN})", 12.5f, Palette.body))
            }
            evidence("Forecasts stored on this phone, frozen when made, model ${ForecastScoring.MODEL_VERSION}.",
                "Error = predicted % − actual % at each horizon, interpolated between the readings around it. " +
                    "Shown as the mean absolute error and the average signed bias (positive = too optimistic).",
                "${all.size} forecasts; $pending still pending. A charge or a gap before a horizon removes that horizon from grading.")
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
                    "Top-up = 30 ÷ median 20→50% minutes × 15.",
                "${charges.size} observed charges. Charges with gaps over the polling allowance are skipped.")
        }
    }

    private fun renderNights(stretches: List<IdleStretch>) {
        val rep = IdleAnalysis.report(stretches)
        if (stretches.isEmpty()) {
            section("Asleep and idle", "Collecting…", "Needs at least 4 hours with the screen off and the phone unplugged.")
            return
        }
        section("Asleep and idle", rep.medianRate?.let { "Typically ${f1(it)}% an hour with the screen off." } ?: "Idle drain").apply {
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
        fun fill() {
            holder.removeAllViews()
            holder.addView(chipRow(*listOf(8, 12, 18, 24).map { h ->
                chip("$h h", if (h == targetHours) Palette.navy else Palette.track).apply {
                    setOnClickListener { targetHours = h; fill() }
                }
            }.toTypedArray()))
            val now = System.currentTimeMillis()
            val assessed = devices.zip(data).mapNotNull { (d, readings) ->
                val last = readings.lastOrNull()?.takeIf { now - it.timeMs < 6 * 3_600_000L } ?: return@mapNotNull null
                val rates = BatteryMath.dischargeStretches(readings.map(BatteryPoint::of), gaps = gaps).map { it.drop / it.hours }
                val chargeRate = BatteryMath.median(ChargeCurves.observedCharges(readings, gaps)
                    .map { (it.toPct - it.fromPct) / ((it.endMs - it.startMs) / 60_000.0) })
                ReadinessPlanner.assess(d.second, last.percent, rates, chargeRate, targetHours.toDouble(), reservePct)
            }
            if (assessed.isEmpty()) holder.addView(label("No recent readings to plan with.", 12.5f, Palette.quiet))
            for (r in assessed) {
                val verdict = when (r.meetsTarget) {
                    null -> "not enough history yet"
                    true -> "lasts about ${fmt(r.hoursToReserve!!)} h" + (if (fmt(r.hoursToReserveFast!!) == fmt(r.hoursToReserveSlow!!)) ""
                        else " (${fmt(r.hoursToReserveFast)}–${fmt(r.hoursToReserveSlow)} h)") + " ✓"
                    false -> "falls short after about ${fmt(r.hoursToReserve!!)} h" +
                        (r.topUpMinutes?.let { "; charge ${fmt(it.coerceAtLeast(1.0))} min first" } ?: "")
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
                    cell(if (h <= 0) "–" else "${f1(h)} h", (25 + 200 * h / most).toInt())
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
                val verdict = if (e.category == "note") null else UpdateImpactAnalysis.assess(e, stretches).let { im ->
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

    private companion object {
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
                val v = input.text.toString().toDoubleOrNull()
                if (v != null && v in 1.0..200.0) {
                    PollScheduler.prefs(this).edit().putFloat(KEY_CENTS_PER_KWH, v.toFloat()).apply()
                    render()
                }
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
    private fun shortWhen(ms: Long) = java.text.SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))
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
