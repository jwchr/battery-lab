package com.batterylab.personal

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context

object PollScheduler {
    private const val JOB_ID = 44007
    const val INTERVAL_MINUTES = "poll_interval_minutes"
    /** Choices offered in the app. 15 min is Android's floor for periodic jobs. */
    val INTERVAL_CHOICES = listOf(15, 30, 60, 120, 240)
    const val PREFS = "battery_settings"
    const val RING_ADDRESS = "ring_address"
    const val RING_ENABLED = "ring_monitoring_enabled"

    fun schedule(context: Context) {
        PowerConnectionReceiver.ensureRegistered(context)
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        // Re-scheduling an existing job replaces it, and Android then runs it right away.
        // That made every app open trigger an extra sample (and, with Ring monitoring on,
        // an extra Ring BLE wakeup). Keep the existing job; it reads settings when it runs.
        val existing = scheduler.getPendingJob(JOB_ID)
        val interval = intervalMinutes(context) * 60_000L
        if (existing != null && existing.isPeriodic && existing.intervalMillis == interval) return
        // Periodic jobs are opportunistic. Sample timestamps reflect actual collection time.
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, BatteryPollService::class.java))
            .setPeriodic(interval)
            .setPersisted(true)
            .build())
    }

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun intervalMinutes(context: Context) =
        prefs(context).getInt(INTERVAL_MINUTES, 15).takeIf { it in INTERVAL_CHOICES } ?: 15

    /** Changing the interval replaces the job, which also runs it once right away. */
    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(INTERVAL_MINUTES, minutes).apply()
        schedule(context)
    }

    /** Readings further apart than this are a gap in the record, not a measured stretch. */
    fun gapLimits(context: Context) = GapLimits.forInterval(intervalMinutes(context))

    fun intervalLabel(minutes: Int) = if (minutes < 60) "$minutes min" else if (minutes == 60) "hour" else "${minutes / 60} hours"

    fun ringEnabled(context: Context) = prefs(context).getBoolean(RING_ENABLED, false)
    fun ringAddress(context: Context) = prefs(context).getString(RING_ADDRESS, null)

    /** One Ring history whatever the source; the Bluetooth address is never part of it. */
    @Suppress("UNUSED_PARAMETER")
    fun ringDeviceId(context: Context) = BatteryStore.RING_ID
    fun setRingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(RING_ENABLED, enabled).apply()
        schedule(context)
    }
}

private const val KEY_LAST_BUCKET = "last_standby_bucket"
private const val KEY_LAST_CAPABILITY = "last_capability_probe"

/** One background worker for database, analysis and file work, so the main thread never waits on it. */
object Background {
    val io: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "battery-lab-io").apply { isDaemon = true }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun onMain(block: () -> Unit) { main.post(block) }
    fun onMainDelayed(delayMs: Long, block: () -> Unit) { main.postDelayed(block, delayMs) }
}

class BatteryPollService : JobService() {
    private var reader: RingReader? = null
    @Volatile private var stopped = false

    /**
     * Runs on the main thread, so it only does what must happen there: render Samsung's widget
     * offscreen. Saving readings and the backup happen on [Background.io]; the job finishes
     * once that work (and the optional Ring read) is done.
     */
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        PowerConnectionReceiver.ensureRegistered(this)
        val store = BatteryStore.get(this)
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val startedCpu = android.os.Process.getElapsedCpuTime()
        val snapshot = try { SamsungWidgetSource.snapshot(this, includeIcons = false) to null } catch (e: Exception) { null to e }
        Background.io.execute {
            try {
                CloudBackupAgent.importPending(this)
                logStandbyBucketChange(store)
                PhoneBattery.read(this, "job")?.let(store::append)
                snapshot.first?.let { SamsungWidgetSource.record(this, store, it) }
                snapshot.second?.let { store.logProbe(SamsungWidgetSource.SOURCE, "Widget read failed: ${it.javaClass.simpleName}: ${it.message}") }
                // Database-only analysis: update journal, score/issue forecasts, refresh capabilities daily.
                ContextWatcher.check(this, store)
                ForecastKeeper.tick(this, store, listOf("phone", SamsungWidgetSource.WATCH_DEVICE, BatteryStore.RING_ID))
                refreshCapabilitiesIfDue(store)
                // Phone-side only: writes the backup file (at most every 12 h); the Drive app uploads it.
                if (!stopped) DriveBackup.maybeBackup(this, store)
            } catch (e: Throwable) {
                // Throwable, not Exception: an Error here would kill the single io thread's process.
                try { store.logProbe("job", "Background work failed: ${e.javaClass.simpleName}") } catch (_: Throwable) { }
            }
            try {
                Overhead.logPoll(this, store, android.os.SystemClock.elapsedRealtime() - startedMs,
                    android.os.Process.getElapsedCpuTime() - startedCpu)
            } catch (_: Throwable) { }
            Background.onMain { readRingOrFinish(params, store) }
        }
        return true
    }

    private fun readRingOrFinish(params: JobParameters, store: BatteryStore) {
        if (stopped) return
        val address = PollScheduler.ringAddress(this)
        if (!PollScheduler.ringEnabled(this) || address == null) {
            jobFinished(params, false)
            return
        }
        val bleStart = android.os.SystemClock.elapsedRealtime()
        reader = RingReader(applicationContext, address) { reading, error ->
            reader = null
            Background.io.execute {
                try {
                    Overhead.logBle(store, android.os.SystemClock.elapsedRealtime() - bleStart, reading != null)
                    if (reading != null) store.append(reading)
                    if (error != null) store.logProbe(BatteryStore.RING_ID, error)
                } catch (_: Throwable) { }
                // Always finish, or the job holds its wakelock until the system stops it.
                Background.onMain { if (!stopped) jobFinished(params, false) }
            }
        }.also { it.start() }
    }

    private fun refreshCapabilitiesIfDue(store: BatteryStore) {
        val prefs = PollScheduler.prefs(this)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CAPABILITY, 0L) < 24 * 3_600_000L) return
        CapabilityProbe.refresh(this, store)
        prefs.edit().putLong(KEY_LAST_CAPABILITY, now).apply()
    }

    /**
     * Adaptive Battery can demote the app to RARE (40), where job quotas stop the 15-minute
     * poll for hours. Logging each change explains gaps in background history.
     */
    private fun logStandbyBucketChange(store: BatteryStore) {
        val bucket = (getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
        val prefs = PollScheduler.prefs(this)
        if (prefs.getInt(KEY_LAST_BUCKET, -1) == bucket) return
        prefs.edit().putInt(KEY_LAST_BUCKET, bucket).apply()
        store.logProbe("job", "Standby bucket now $bucket")
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        reader?.cancel()
        reader = null
        return false
    }
}
