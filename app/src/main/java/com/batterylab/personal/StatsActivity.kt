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
        store = BatteryStore(this)
        setUpPage()
        render()
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
        val data = devices.map { (id, _, _) -> store.since(id, 0L) }
        val gaps = PollScheduler.gapLimits(this)
        val stats = data.map { BatteryStats.device(it, hourOf, gaps) }
        val phone = data[0]

        renderPhoneHealth(phone)
        renderDrain(phone, gaps)
        renderNumbers(devices, stats)
        renderHabits(stats[0])
        renderHours(devices, stats)
        renderShowdown(devices, data)
        renderRolling(devices, data)
        BatteryStats.temperature(phone)?.let(::renderTemperature)
        renderFun(stats[0], phoneCapacity(phone), phone)
        body.addView(label("All numbers come from readings stored on this phone. Estimates, not lab measurements.",
            11.5f, Palette.body).apply { setPadding(dp(6), dp(4), dp(6), 0) })
    }

    /** Full capacity: the charge counter at the most recent 100% reading, else the median implied value. */
    private fun phoneCapacity(phone: List<Reading>): Double? =
        phone.lastOrNull { it.percent == 100 && (it.remainingUah ?: 0) > 0 }?.remainingUah?.div(1000.0)
            ?: BatteryMath.median(phone.mapNotNull { BatteryMath.impliedCapacityMah(it.percent, it.remainingUah, it.charging) })

    private fun renderPhoneHealth(phone: List<Reading>) {
        val capacity = phoneCapacity(phone)
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
        section("Phone battery health", pct?.let { "${String.format(Locale.US, "%.1f", it)}% of new." } ?: "Measuring…",
            tag = health).apply {
            addView(LinearLayout(this@StatsActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(4))
                addView(GaugeView(this@StatsActivity, pct?.roundToInt()?.coerceAtMost(100), Palette.green),
                    LinearLayout.LayoutParams(dp(84), dp(84)))
                addView(label(buildString {
                    if (capacity != null) append("Holds about ${fmt(capacity)} mAh when full.")
                    if (spec != null) append("\nNew: ${fmt(spec.typicalMah)} mAh advertised, ${fmt(spec.ratedMah)} mAh rated minimum.")
                    if (capacity != null && spec != null && capacity > spec.ratedMah)
                        append("\nStill above the rated minimum.")
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
            if (spec == null) addView(label("Health % needs this model's factory capacity, which Battery Lab doesn't know yet.", 11.5f, Palette.quiet))
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
        section("Over time", "How long a full charge lasts.", "Rolling averages of every discharge in each window, " +
            "weighted by how much battery it used. Longer windows fill in as history grows.").apply {
            devices.forEachIndexed { i, (_, name, color) ->
                val stretches = BatteryMath.dischargeStretches(data[i].map { BatteryPoint(it.timeMs, it.percent, it.charging) }, gaps = PollScheduler.gapLimits(this@StatsActivity))
                if (stretches.isEmpty()) return@forEachIndexed
                val first = data[i].first().timeMs
                val rows = windows.mapNotNull { (labelText, span) ->
                    val inWindow = stretches.filter { it.startMs >= now - span }
                    if (inWindow.isEmpty()) null else Triple(labelText, 100.0 * inWindow.sumOf { it.hours } / inWindow.sumOf { it.drop },
                        if (now - first < span && span < Long.MAX_VALUE / 2) "only ${f1((now - first) / day.toDouble())} days so far" else "${inWindow.size} discharge${if (inWindow.size == 1) "" else "s"}")
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
        val cents = wh / 1000 * CENTS_PER_KWH
        val fullCharge = BatteryStats.wattHours(100, cap)
        section("What it costs", "${money(cents)} of electricity so far.",
            "At about ${CENTS_PER_KWH.roundToInt()}¢ per kWh, PSE&G's typical all-in home rate in 2026.").apply {
            addTiles(listOfNotNull(
                Triple(money(fullCharge / 1000 * CENTS_PER_KWH), "per full charge", "${f1(fullCharge)} Wh"),
                if (days >= 1.0) Triple(money(cents / days * 365), "a year at this pace", "for the phone") else null,
                Triple("${fmt(wh / 1000 / FRIDGE_KWH_PER_DAY * 24 * 60)} min", "of a fridge", "a typical fridge uses about 1.5 kWh a day"),
                Triple("${fmt(wh / EV_WH_PER_METER)} m", "in an electric car", "at about 300 Wh per mile"),
            ))
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
    private fun renderDrain(phone: List<Reading>, gaps: GapLimits) {
        if (!UsageSource.granted(this)) {
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
        val from = now - 7 * 86_400_000L
        val recent = phone.filter { it.timeMs >= from }
        val (screen, apps) = UsageSource.load(this, from, now)
        val d = DrainAnalysis.analyze(recent.map { BatteryPoint(it.timeMs, it.percent, it.charging) }, screen, apps, gaps.stepMs)
        if (d.totalDrop < 5) {
            section("What's draining it", "Collecting…", "Needs a few hours of the phone off the charger.")
            return
        }
        val spanDays = (now - (recent.firstOrNull()?.timeMs ?: now)) / 86_400_000.0
        section("What's draining it", "Where your phone's battery went.",
            "Last ${f1(spanDays)} days. Estimated from 1% steps, so it sharpens with more days.").apply {
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
                "${f1(a.percent / d.totalDrop * 100)}%", "${f1(a.foregroundHours)} h")) }
            if (top.isNotEmpty()) addView(label("Share of all battery used, on top of idle drain, and time on screen.",
                11.5f, Palette.quiet).apply { setPadding(0, dp(8), 0, 0) })
        }
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

    /** Two-column grid of stat tiles: big value, small label, optional caption. */
    private fun LinearLayout.addTiles(tiles: List<Triple<String, String, String?>>) {
        tiles.chunked(2).forEach { row ->
            addView(LinearLayout(this@StatsActivity).apply {
                row.forEachIndexed { i, (value, labelText, caption) ->
                    addView(LinearLayout(this@StatsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(9), dp(10), dp(9))
                        background = rounded(Color.argb(150, 255, 255, 255), 14, Palette.hairline)
                        // Value and label share a baseline: "76%  used by apps on screen".
                        // One TextView with two sizes, so a long label wraps cleanly instead of clipping.
                        addView(TextView(this@StatsActivity).apply {
                            val s = android.text.SpannableStringBuilder(value)
                            s.setSpan(android.text.style.AbsoluteSizeSpan(20, true), 0, value.length, 0)
                            s.setSpan(android.text.style.ForegroundColorSpan(ink), 0, value.length, 0)
                            s.setSpan(android.text.style.TypefaceSpan(medium), 0, value.length, 0)
                            s.append("  ").append(labelText)
                            text = s; textSize = 12.5f; setTextColor(Palette.body)
                            setLineSpacing(0f, 0.95f)
                        })
                        if (caption != null) addView(TextView(this@StatsActivity).apply {
                            text = caption; textSize = 11f; setTextColor(Palette.quiet)
                        })
                    }, LinearLayout.LayoutParams(0, if (row.size == 1) -2 else -1, 1f).apply { if (i == 0) rightMargin = dp(8) })
                }
                if (row.size == 1) addView(View(this@StatsActivity), LinearLayout.LayoutParams(0, 0, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private companion object {
        const val CENTS_PER_KWH = 26.0
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
