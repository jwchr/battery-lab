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

/** One app's estimated share of the battery drain in the analysed window. */
data class AppDrain(val app: String, val foregroundHours: Double, val percent: Double)

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
) {
    /** False until there's at least an hour of screen-off history to measure idle drain from. */
    val baselineKnown: Boolean get() = screenOffRate != null
}

/** Android's screen and app history for a window, and where that history actually begins. */
data class UsageWindow(val screenOn: List<Span>, val apps: Map<String, List<Span>>, val coverageStartMs: Long?)

/**
 * Splits observed phone drain into "idle" and per-app shares. Each reading step on battery is
 * split by how much of it the screen was on. The idle rate comes from screen-off time; anything a
 * step lost beyond that while the screen was on is shared among the apps on screen during it, in
 * proportion to their time. It's an estimate from 1% steps, so it gets better with more days.
 */
object DrainAnalysis {
    private const val HOUR = 3_600_000.0
    const val UNATTRIBUTED = "Other"

    fun analyze(points: List<BatteryPoint>, screenOn: List<Span>, apps: Map<String, List<Span>>,
                maxStepMs: Long): DrainBreakdown {
        data class Step(val from: Long, val to: Long, val drop: Int, val onMs: Long)
        val p = points.filter { it.percent in 0..100 }.sortedBy { it.timeMs }
        val steps = p.zipWithNext().mapNotNull { (a, b) ->
            val dt = b.timeMs - a.timeMs
            if (a.charging == true || b.charging == true || dt <= 0 || dt > maxStepMs || b.percent > a.percent) null
            else Step(a.timeMs, b.timeMs, a.percent - b.percent, screenOn.sumOf { it.overlap(a.timeMs, b.timeMs) }.coerceAtMost(dt))
        }
        val onMs = steps.sumOf { it.onMs }
        val offMs = steps.sumOf { (it.to - it.from) - it.onMs }
        val totalDrop = steps.sumOf { it.drop }.toDouble()

        // Idle rate from steps that were (almost) all screen-off.
        val dark = steps.filter { it.onMs * 20 <= it.to - it.from }
        val darkHours = dark.sumOf { it.to - it.from } / HOUR
        val offRate = if (darkHours >= 1.0) dark.sumOf { it.drop } / darkHours else null
        // Unknown idle drain is not zero idle drain: without a baseline, nothing is split.
        if (offRate == null) return DrainBreakdown(onMs / HOUR, offMs / HOUR, null, null, totalDrop, 0.0, emptyList())
        val idle = offRate

        var standby = 0.0
        val share = HashMap<String, Double>()
        val fgMs = HashMap<String, Long>()
        for (s in steps) {
            val hours = (s.to - s.from) / HOUR
            val base = min(s.drop.toDouble(), idle * hours)
            standby += base
            val extra = s.drop - base
            val inStep = apps.mapValues { (_, spans) -> spans.sumOf { it.overlap(s.from, s.to) } }.filterValues { it > 0 }
            inStep.forEach { (app, ms) -> fgMs[app] = (fgMs[app] ?: 0L) + ms }
            if (extra <= 0.0) continue
            val total = inStep.values.sum()
            if (total == 0L) share[UNATTRIBUTED] = (share[UNATTRIBUTED] ?: 0.0) + extra
            else inStep.forEach { (app, ms) -> share[app] = (share[app] ?: 0.0) + extra * ms / total }
        }
        // Screen-on drain can't really be below idle drain; if the fit says so, the data is too thin.
        val onRate = if (onMs >= HOUR / 2) {
            ((totalDrop - offRate * offMs / HOUR) / (onMs / HOUR)).takeIf { it >= offRate }
        } else null
        val list = (share.keys + fgMs.keys).distinct().map { app ->
            AppDrain(app, (fgMs[app] ?: 0L) / HOUR, share[app] ?: 0.0)
        }.filter { it.percent > 0.0 }.sortedByDescending { it.percent }
        return DrainBreakdown(onMs / HOUR, offMs / HOUR, offRate, onRate, totalDrop, standby, list)
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
            }
        }
        screenSince?.let { if (toMs > it) screen += Span(it, toMs) }
        closeApp(toMs)
        // Private apps are folded into "Other" here, before anything else sees their names.
        // If a hidden list exists but can't be read, hide every app rather than risk showing one.
        val hidden = privateApps(context)
        val folded = HashMap<String, MutableList<Span>>()
        apps.forEach { (pkg, spans) ->
            val hide = hidden == null || pkg in hidden
            folded.getOrPut(if (hide) DrainAnalysis.UNATTRIBUTED else pkg) { mutableListOf() } += spans
        }
        return UsageWindow(screen, folded, coverage)
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
