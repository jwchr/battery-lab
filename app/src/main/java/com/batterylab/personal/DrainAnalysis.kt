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
)

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
        val idle = offRate ?: 0.0

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
        val onRate = if (onMs >= HOUR / 2 && offRate != null) {
            (totalDrop - offRate * offMs / HOUR) / (onMs / HOUR)
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
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Screen-on spans and per-package on-screen spans between [fromMs] and [toMs]. */
    fun load(context: Context, fromMs: Long, toMs: Long): Pair<List<Span>, Map<String, List<Span>>> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList<Span>() to emptyMap()
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
                UsageEvents.Event.SCREEN_INTERACTIVE -> { screenKnown = true; if (screenSince == null) screenSince = t }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    val since = screenSince ?: if (!screenKnown) fromMs else null
                    if (since != null && t > since) screen += Span(since, t)
                    screenSince = null; screenKnown = true
                    closeApp(t)
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (e.packageName != app) { closeApp(t); app = e.packageName; appSince = t }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (e.packageName == app) closeApp(t)
            }
        }
        screenSince?.let { if (toMs > it) screen += Span(it, toMs) }
        closeApp(toMs)
        return screen to apps
    }

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
