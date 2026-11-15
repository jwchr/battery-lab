package com.batterylab.personal

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlin.math.max
import kotlin.math.min

/** A stretch of time, [startMs] inclusive to [endMs] exclusive. */
data class Span(val startMs: Long, val endMs: Long) {
    fun overlap(from: Long, to: Long): Long = max(0L, min(endMs, to) - max(startMs, from))
}

/**
 * One app's estimated share of the battery drain in the analysed window.
 * [percent] is its share above idle; [mahUsed] the same in mAh when capacity is known.
 * [activePctPerHour] and [activeWatts] are the whole phone's drain while this app was on screen
 * (including idle), for apps with at least 10 minutes on screen. [backgroundServiceHours] is time
 * it ran a foreground service with the screen off.
 */
data class AppDrain(
    val app: String,
    val foregroundHours: Double,
    val percent: Double,
    val mahUsed: Double? = null,
    val activePctPerHour: Double? = null,
    val activeWatts: Double? = null,
    val backgroundServiceHours: Double = 0.0
)

data class DrainBreakdown(
    val screenOnHours: Double,
    val screenOffHours: Double,
    /** % per hour with the screen off: the phone's "idle" cost. */
    val screenOffRate: Double?,
    /** % per hour with the screen on, including that idle cost. */
    val screenOnRate: Double?,
    val totalDrop: Double,
    /** Drop explained by idle drain, both screen-off and running underneath screen-on time. */
    val standbyDrop: Double,
    /** Drop on top of idle while the screen was on, split across apps by time on screen. */
    val apps: List<AppDrain>,
    /** True when step sizes came from the charge counter rather than whole percentages. */
    val usedCounter: Boolean = false
) {
    /** False until there's at least an hour of screen-off history to measure idle drain from. */
    val baselineKnown: Boolean get() = screenOffRate != null
}

/** Android's screen and app history for a window, and where that history actually begins. */
data class UsageWindow(
    val screenOn: List<Span>,
    val apps: Map<String, List<Span>>,
    val coverageStartMs: Long?,
    /** Foreground-service spans per app (Android 10+). */
    val services: Map<String, List<Span>> = emptyMap()
)

/** Fast overlap queries against one app's sorted, non-overlapping spans (binary search + prefix sums). */
class SpanIndex(spans: List<Span>) {
    private val sorted = spans.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
    private val starts = LongArray(sorted.size) { sorted[it].startMs }
    private val prefix = LongArray(sorted.size + 1).also { p ->
        for (i in sorted.indices) p[i + 1] = p[i] + (sorted[i].endMs - sorted[i].startMs)
    }

    /** Total covered time before [t]. */
    private fun coveredBefore(t: Long): Long {
        var k = java.util.Arrays.binarySearch(starts, t)
        if (k < 0) k = -k - 1            // spans [0, k) start before t
        if (k == 0) return 0
        val last = sorted[k - 1]
        return prefix[k - 1] + (min(last.endMs, t) - last.startMs)
    }

    fun overlap(from: Long, to: Long): Long = if (to <= from) 0 else max(0L, coveredBefore(to) - coveredBefore(from))
}

/**
 * Splits observed phone drain into "idle" and per-app shares. Each reading step on battery is
 * split by how much of it the screen was on. The idle rate comes from screen-off time; anything a
 * step lost beyond that while the screen was on is shared among the apps on screen during it, in
 * proportion to their time. Step sizes use the charge counter when both ends have it and the
 * capacity is known, otherwise whole percentages.
 */
object DrainAnalysis {
    private const val HOUR = 3_600_000.0
    const val UNATTRIBUTED = "Other"

    fun analyze(points: List<BatteryPoint>, screenOn: List<Span>, apps: Map<String, List<Span>>,
                maxStepMs: Long, capacityMah: Double? = null,
                services: Map<String, List<Span>> = emptyMap()): DrainBreakdown {
        data class Step(val from: Long, val to: Long, val drop: Double, val onMs: Long, val mvAvg: Double?)
        val screen = SpanIndex(screenOn)
        val p = points.filter { it.percent in 0..100 }.sortedBy { it.timeMs }
        var counterSteps = 0
        val steps = p.zipWithNext().mapNotNull { (a, b) ->
            val dt = b.timeMs - a.timeMs
            if (a.charging == true || b.charging == true || dt <= 0 || dt > maxStepMs || b.percent > a.percent) return@mapNotNull null
            val ra = a.remainingUah; val rb = b.remainingUah
            // Counter step in % of capacity; rejected if the counter reset or jumped up.
            val counterDrop = if (capacityMah != null && capacityMah > 0 && ra != null && rb != null && rb <= ra + 1000)
                max(0.0, (ra - rb) / (capacityMah * 10.0)) else null
            if (counterDrop != null) counterSteps++
            val mv = listOfNotNull(a.voltageMv, b.voltageMv).takeIf { it.isNotEmpty() }?.average()
            Step(a.timeMs, b.timeMs, counterDrop ?: (a.percent - b.percent).toDouble(), screen.overlap(a.timeMs, b.timeMs).coerceAtMost(dt), mv)
        }
        val usedCounter = steps.isNotEmpty() && counterSteps * 2 >= steps.size
        val onMs = steps.sumOf { it.onMs }
        val offMs = steps.sumOf { (it.to - it.from) - it.onMs }
        val totalDrop = steps.sumOf { it.drop }

        // Idle rate from steps that were (almost) all screen-off.
        val dark = steps.filter { it.onMs * 20 <= it.to - it.from }
        val darkHours = dark.sumOf { it.to - it.from } / HOUR
        val offRate = if (darkHours >= 1.0) dark.sumOf { it.drop } / darkHours else null
        // Unknown idle drain is not zero idle drain: without a baseline, nothing is split.
        if (offRate == null) return DrainBreakdown(onMs / HOUR, offMs / HOUR, null, null, totalDrop, 0.0, emptyList(), usedCounter)

        val index = apps.mapValues { SpanIndex(it.value) }
        var standby = 0.0
        val share = HashMap<String, Double>()
        val fgMs = HashMap<String, Long>()
        // Whole-phone drop and energy during each app's on-screen time, for intensity.
        val activeDrop = HashMap<String, Double>()
        val activeWh = HashMap<String, Double>()
        for (s in steps) {
            val hours = (s.to - s.from) / HOUR
            val base = min(s.drop, offRate * hours)
            standby += base
            val extra = s.drop - base
            val inStep = index.mapValues { (_, ix) -> ix.overlap(s.from, s.to) }.filterValues { it > 0 }
            val stepMs = (s.to - s.from).toDouble()
            inStep.forEach { (app, ms) ->
                fgMs[app] = (fgMs[app] ?: 0L) + ms
                val part = s.drop * ms / stepMs
                activeDrop[app] = (activeDrop[app] ?: 0.0) + part
                if (capacityMah != null && s.mvAvg != null) activeWh[app] = (activeWh[app] ?: 0.0) + part / 100.0 * capacityMah * s.mvAvg / 1_000_000.0
            }
            if (extra <= 0.0) continue
            val total = inStep.values.sum()
            if (total == 0L) share[UNATTRIBUTED] = (share[UNATTRIBUTED] ?: 0.0) + extra
            else inStep.forEach { (app, ms) -> share[app] = (share[app] ?: 0.0) + extra * ms / total }
        }
        // Screen-on drain can't really be below idle drain; if the fit says so, the data is too thin.
        val onRate = if (onMs >= HOUR / 2) {
            ((totalDrop - offRate * offMs / HOUR) / (onMs / HOUR)).takeIf { it >= offRate }
        } else null
        // Foreground services running while the screen was off.
        val screenOffServiceHours = services.mapValues { (_, spans) ->
            spans.sumOf { sp -> (sp.endMs - sp.startMs) - screen.overlap(sp.startMs, sp.endMs) } / HOUR
        }
        val list = (share.keys + fgMs.keys + screenOffServiceHours.filterValues { it > 0 }.keys).distinct().map { app ->
            val fgHours = (fgMs[app] ?: 0L) / HOUR
            val pct = share[app] ?: 0.0
            val intense = fgHours >= 10 / 60.0
            AppDrain(app, fgHours, pct,
                mahUsed = capacityMah?.let { pct / 100.0 * it },
                activePctPerHour = if (intense) (activeDrop[app] ?: 0.0) / fgHours else null,
                activeWatts = if (intense) activeWh[app]?.let { it / fgHours } else null,
                backgroundServiceHours = screenOffServiceHours[app] ?: 0.0)
        }.filter { it.percent > 0.0 || it.backgroundServiceHours > 0.0 }.sortedByDescending { it.percent }
        return DrainBreakdown(onMs / HOUR, offMs / HOUR, offRate, onRate, totalDrop, standby, list, usedCounter)
    }
}

/** Reads screen and app-on-screen history from Android's usage data (needs Usage access). */
object UsageSource {
    fun granted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Screen-on spans and per-package on-screen spans between [fromMs] and [toMs]. Android keeps
     * only a few days of events, so coverage starts at the first screen event it still has;
     * before that the screen state is unknown, not "off".
     */
    fun load(context: Context, fromMs: Long, toMs: Long): UsageWindow {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return UsageWindow(emptyList(), emptyMap(), null)
        var coverage: Long? = null
        val events = usm.queryEvents(fromMs, toMs)
        val e = UsageEvents.Event()
        val screen = mutableListOf<Span>()
        val apps = HashMap<String, MutableList<Span>>()
        var screenSince: Long? = null
        var screenKnown = false
        var app: String? = null
        var appSince = 0L
        val services = HashMap<String, MutableList<Span>>()
        val serviceSince = HashMap<String, Long>()
        fun closeApp(at: Long) {
            val a = app ?: return
            if (at > appSince) apps.getOrPut(a) { mutableListOf() } += Span(appSince, at)
            app = null
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (coverage == null) coverage = t
                    screenKnown = true; if (screenSince == null) screenSince = t
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (coverage == null) coverage = t
                    if (screenKnown) screenSince?.let { if (t > it) screen += Span(it, t) }
                    screenSince = null; screenKnown = true
                    closeApp(t)
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (screenKnown && e.packageName != app) { closeApp(t); app = e.packageName; appSince = t }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (e.packageName == app) closeApp(t)
                FOREGROUND_SERVICE_START -> serviceSince.putIfAbsent(e.packageName, t)
                FOREGROUND_SERVICE_STOP -> serviceSince.remove(e.packageName)?.let {
                    if (t > it) services.getOrPut(e.packageName) { mutableListOf() } += Span(it, t)
                }
                DEVICE_SHUTDOWN -> {
                    // Nothing runs while the phone is off: close everything at shutdown.
                    if (screenKnown) screenSince?.let { if (t > it) screen += Span(it, t) }
                    screenSince = null
                    closeApp(t)
                    serviceSince.forEach { (pkg, since) -> if (t > since) services.getOrPut(pkg) { mutableListOf() } += Span(since, t) }
                    serviceSince.clear()
                }
            }
        }
        screenSince?.let { if (toMs > it) screen += Span(it, toMs) }
        closeApp(toMs)
        serviceSince.forEach { (pkg, since) -> if (toMs > since) services.getOrPut(pkg) { mutableListOf() } += Span(since, toMs) }
        // Private apps are folded into "Other" here, before anything else sees their names.
        // If a hidden list exists but can't be read, hide every app rather than risk showing one.
        val hidden = privateApps(context)
        fun fold(source: Map<String, List<Span>>): Map<String, List<Span>> {
            val folded = HashMap<String, MutableList<Span>>()
            source.forEach { (pkg, spans) ->
                val hide = hidden == null || pkg in hidden
                folded.getOrPut(if (hide) DrainAnalysis.UNATTRIBUTED else pkg) { mutableListOf() } += spans
            }
            return folded
        }
        return UsageWindow(screen, fold(apps), coverage, fold(services))
    }

    /**
     * Package names the user wants counted as "Other" and never shown by name. Kept only in a
     * file in the app's private storage on the phone (one package per line), never in the code,
     * the history database or any backup.
     */
    private fun privateApps(context: Context): Set<String>? {
        val file = java.io.File(context.filesDir, PRIVATE_APPS_FILE)
        if (!file.exists()) return emptySet()
        return try {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Exception) { null }
    }

    /** How many apps are hidden (never which), or null if the list can't be read. */
    fun hiddenCount(context: Context): Int? = privateApps(context)?.size

    fun hideApps(context: Context, packages: Collection<String>) {
        val current = privateApps(context) ?: return
        java.io.File(context.filesDir, PRIVATE_APPS_FILE).writeText((current + packages).joinToString("\n") + "\n")
    }

    fun unhideAll(context: Context) { java.io.File(context.filesDir, PRIVATE_APPS_FILE).delete() }

    const val PRIVATE_APPS_FILE = "private_apps.txt"
    // UsageEvents.Event constants (API 29+), named here so older SDK stubs aren't needed.
    private const val FOREGROUND_SERVICE_START = 19
    private const val FOREGROUND_SERVICE_STOP = 20
    private const val DEVICE_SHUTDOWN = 26

    /** The app's display name, or a tidy version of its package name if it isn't visible. */
    fun label(context: Context, pkg: String): String = when (pkg) {
        DrainAnalysis.UNATTRIBUTED -> pkg
        else -> try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}
