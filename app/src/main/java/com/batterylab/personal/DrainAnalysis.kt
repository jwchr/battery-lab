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

    /** Sorted, non-overlapping union of [spans]. */
    fun mergeSpans(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        for (sp in spans.filter { it.endMs > it.startMs }.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && sp.startMs <= last.endMs) out[out.lastIndex] = Span(last.startMs, max(last.endMs, sp.endMs))
            else out += sp
        }
        return out
    }

    fun analyze(points: List<BatteryPoint>, screenOn: List<Span>, apps: Map<String, List<Span>>,
                maxStepMs: Long, capacityMah: Double? = null,
                services: Map<String, List<Span>> = emptyMap()): DrainBreakdown {
        data class Step(val from: Long, val to: Long, val drop: Double, val onMs: Long, val mvAvg: Double?)
        val screen = SpanIndex(screenOn)
        val p = BatteryMath.withoutSpikes(points.filter { it.percent in 0..100 }.sortedBy { it.timeMs },
            GapLimits(stepMs = maxStepMs))
        var counterSteps = 0
        val steps = p.zipWithNext().mapNotNull { (a, b) ->
            val dt = b.timeMs - a.timeMs
            if (a.charging == true || b.charging == true || dt <= 0 || dt > maxStepMs || b.percent > a.percent) return@mapNotNull null
            val ra = a.remainingUah; val rb = b.remainingUah
            val percentDrop = (a.percent - b.percent).toDouble()
            // Allow percent quantization, but reject counter resets or implausible jumps.
            // Long math and a sign check: a negative error value (Int.MIN_VALUE) in a restored row
            // would otherwise overflow ra - rb and count as a 0% step.
            val counterDrop = if (capacityMah != null && capacityMah > 0 && ra != null && rb != null &&
                ra >= 0 && rb >= 0 && rb.toLong() <= ra.toLong() + 1000)
                max(0.0, (ra.toLong() - rb.toLong()) / (capacityMah * 10.0)).takeIf { it <= percentDrop + 3.0 } else null
            if (counterDrop != null) counterSteps++
            val mv = listOfNotNull(a.voltageMv, b.voltageMv).takeIf { it.isNotEmpty() }?.average()
            Step(a.timeMs, b.timeMs, counterDrop ?: percentDrop, screen.overlap(a.timeMs, b.timeMs).coerceAtMost(dt), mv)
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
        // Idle is priced over the whole window, not step by step: with 1% steps most quarter-hours
        // show no drop and a few show 1, and a per-step min(drop, idle) booked most of every 1%
        // step as app use, even with the screen off (audit review, 2026-11-16).
        val totalHours = steps.sumOf { it.to - it.from } / HOUR
        val standby = min(totalDrop, offRate * totalHours)
        val fgMs = HashMap<String, Long>()
        // Whole-phone drop and energy during each app's on-screen time, for intensity and shares.
        val activeDrop = HashMap<String, Double>()
        val activeWh = HashMap<String, Double>()
        var otherOnMs = 0L; var otherOnDrop = 0.0; var otherOnWh: Double? = null
        for (s in steps) {
            val inStep = index.mapValues { (_, ix) -> ix.overlap(s.from, s.to) }.filterValues { it > 0 }
            val stepMs = (s.to - s.from).toDouble()
            // Screen-off time in the step is charged at the idle rate; what's left of the drop is
            // spread over the on-screen time only, so app rates aren't diluted toward idle. Not
            // clamped per step: a step that lost less than idle would explain carries the shortfall
            // into the totals, which are clamped once below.
            val screenMs = maxOf(s.onMs, inStep.values.sum()).toDouble()
            val onDrop = s.drop - offRate * (stepMs - screenMs).coerceAtLeast(0.0) / HOUR
            fun partFor(ms: Long) = if (screenMs > 0) onDrop * ms / screenMs else 0.0
            inStep.forEach { (app, ms) ->
                fgMs[app] = (fgMs[app] ?: 0L) + ms
                val part = partFor(ms)
                activeDrop[app] = (activeDrop[app] ?: 0.0) + part
                if (capacityMah != null && s.mvAvg != null) activeWh[app] = (activeWh[app] ?: 0.0) + part / 100.0 * capacityMah * s.mvAvg / 1_000_000.0
            }
            // Screen on with no app in the history (lock screen, system UI) counts as Other.
            val bare = (s.onMs - inStep.values.sum()).coerceAtLeast(0L)
            otherOnMs += bare; otherOnDrop += partFor(bare)
            if (bare > 0 && capacityMah != null && s.mvAvg != null)
                otherOnWh = (otherOnWh ?: 0.0) + partFor(bare) / 100.0 * capacityMah * s.mvAvg / 1_000_000.0
        }
        // Steps were pooled unclamped above; no total may go below zero.
        activeDrop.replaceAll { _, v -> max(0.0, v) }; activeWh.replaceAll { _, v -> max(0.0, v) }
        otherOnDrop = max(0.0, otherOnDrop); otherOnWh = otherOnWh?.let { max(0.0, it) }
        // Each app: drop while it was on screen beyond what idle would have used in that time.
        // Screen-off time is never given to an app. Shares are then scaled to add up to exactly
        // the drop above idle, so idle plus every row is 100%.
        val raw = HashMap<String, Double>()
        fgMs.forEach { (app, ms) -> raw[app] = max(0.0, (activeDrop[app] ?: 0.0) - offRate * ms / HOUR) }
        if (otherOnMs > 0) {
            raw[UNATTRIBUTED] = (raw[UNATTRIBUTED] ?: 0.0) + max(0.0, otherOnDrop - offRate * otherOnMs / HOUR)
            // The same screen time also counts toward Other's on-screen hours and intensity.
            fgMs[UNATTRIBUTED] = (fgMs[UNATTRIBUTED] ?: 0L) + otherOnMs
            activeDrop[UNATTRIBUTED] = (activeDrop[UNATTRIBUTED] ?: 0.0) + otherOnDrop
            otherOnWh?.let { activeWh[UNATTRIBUTED] = (activeWh[UNATTRIBUTED] ?: 0.0) + it }
        }
        val above = totalDrop - standby
        val rawSum = raw.values.sum()
        val share = HashMap<String, Double>()
        when {
            above <= 0.0 -> Unit
            rawSum > 0.0 -> raw.forEach { (app, v) -> if (v > 0) share[app] = v / rawSum * above }
            else -> share[UNATTRIBUTED] = above
        }
        // Screen-on drain can't really be below idle drain; if the fit says so, the data is too thin.
        val onRate = if (onMs >= HOUR / 2) {
            ((totalDrop - offRate * offMs / HOUR) / (onMs / HOUR)).takeIf { it >= offRate }
        } else null
        // Foreground services running while the screen was off, only within the analysed steps
        // (not charging time or before the readings start), with overlapping spans merged.
        val stepIndex = SpanIndex(steps.map { Span(it.from, it.to) })
        val screenOffServiceHours = services.mapValues { (_, spans) ->
            mergeSpans(spans).sumOf { sp ->
                val inWindow = stepIndex.overlap(sp.startMs, sp.endMs)
                // Of the in-window time, keep the part with the screen off.
                val onInWindow = steps.sumOf { st ->
                    val from = max(sp.startMs, st.from); val to = min(sp.endMs, st.to)
                    if (to > from) screen.overlap(from, to) else 0L
                }
                (inWindow - onInWindow).coerceAtLeast(0L)
            } / HOUR
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

/**
 * Builds on-screen spans per package from activity events. The resumed activity is tracked by
 * package and class: a late pause or stop from an activity that already gave way to another one
 * (A1 paused, A2 resumed, then A1 stopped) must not end A2's span. Pure so it can be tested.
 */
class ForegroundSpans(private val out: MutableMap<String, MutableList<Span>> = HashMap()) {
    private var pkg: String? = null
    private var cls: String? = null
    private var since = 0L

    val spans: Map<String, List<Span>> get() = out

    /** An activity came to the front. Moving between activities of the same app keeps its span. */
    fun resumed(packageName: String, className: String?, t: Long) {
        if (packageName != pkg) { close(t); pkg = packageName; since = t }
        cls = className
    }

    /** Ends the span only if this is the activity currently on screen. */
    fun pausedOrStopped(packageName: String, className: String?, t: Long) {
        if (packageName == pkg && className == cls) close(t)
    }

    fun close(at: Long) {
        val a = pkg ?: return
        if (at > since) out.getOrPut(a) { mutableListOf() } += Span(since, at)
        pkg = null; cls = null
    }
}

/** Reads screen and app-on-screen history from Android's usage data (needs Usage access). */
object UsageSource {
    internal data class UsageEvent(val type: Int, val packageName: String, val className: String?, val timeMs: Long)
    internal data class ServiceEvent(val type: Int, val packageName: String, val className: String?, val timeMs: Long)

    internal fun serviceSpans(events: List<ServiceEvent>, toMs: Long): Map<String, List<Span>> {
        val spans = HashMap<String, MutableList<Span>>()
        val since = HashMap<Pair<String, String>, Long>()
        fun record(pkg: String, start: Long, end: Long) {
            if (end > start) spans.getOrPut(pkg) { mutableListOf() } += Span(start, end)
        }
        fun closeAll(at: Long) {
            since.forEach { (key, start) -> record(key.first, start, at) }
            since.clear()
        }
        for (event in events) {
            val key = event.packageName to (event.className ?: "")
            when (event.type) {
                FOREGROUND_SERVICE_START -> since.putIfAbsent(key, event.timeMs)
                FOREGROUND_SERVICE_STOP -> since.remove(key)?.let { record(event.packageName, it, event.timeMs) }
                DEVICE_SHUTDOWN -> closeAll(event.timeMs)
            }
        }
        closeAll(toMs)
        return spans
    }

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
        val events = usm.queryEvents(fromMs, toMs)
        val e = UsageEvents.Event()
        // Streamed one event at a time: a month of usage events can run to hundreds of thousands.
        val history = sequence {
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                yield(UsageEvent(e.eventType, e.packageName ?: "", e.className, e.timeStamp))
            }
        }
        val window = fromEvents(history, toMs)
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
        return window.copy(apps = fold(window.apps), services = fold(window.services))
    }

    /** Builds spans from chronological events without needing Android or a Context. */
    internal fun fromEvents(events: List<UsageEvent>, toMs: Long): UsageWindow = fromEvents(events.asSequence(), toMs)

    internal fun fromEvents(events: Sequence<UsageEvent>, toMs: Long): UsageWindow {
        var coverage: Long? = null
        val screen = mutableListOf<Span>()
        val apps = HashMap<String, MutableList<Span>>()
        var screenSince: Long? = null
        var screenKnown = false
        val foreground = ForegroundSpans(apps)
        val serviceEvents = mutableListOf<ServiceEvent>()
        var lastEventMs: Long? = null
        fun closeApp(at: Long) = foreground.close(at)
        fun closeAll(at: Long) {
            if (screenKnown) screenSince?.let { if (at > it) screen += Span(it, at) }
            screenSince = null
            closeApp(at)
            serviceEvents += ServiceEvent(DEVICE_SHUTDOWN, "", null, at)
        }
        for (e in events) {
            val t = e.timeMs
            when (e.type) {
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
                    if (screenKnown) foreground.resumed(e.packageName, e.className, t)
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    foreground.pausedOrStopped(e.packageName, e.className, t)
                FOREGROUND_SERVICE_START, FOREGROUND_SERVICE_STOP ->
                    serviceEvents += ServiceEvent(e.type, e.packageName, e.className, t)
                DEVICE_SHUTDOWN -> closeAll(t)
                // A crash may leave no shutdown event. Only the last pre-boot event is known
                // to have happened while the old session was still running.
                DEVICE_STARTUP -> lastEventMs?.let { closeAll(it) }
            }
            lastEventMs = t
        }
        screenSince?.let { if (toMs > it) screen += Span(it, toMs) }
        closeApp(toMs)
        return UsageWindow(screen, apps, coverage, serviceSpans(serviceEvents, toMs))
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
    private const val DEVICE_STARTUP = 27

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
