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
    /** Whether Ring monitoring was on before the running test started; null when unknown. */
    const val RING_BEFORE_TEST = "ring_monitoring_before_test"
    fun ringBeforeTest(context: Context): Boolean? =
        prefs(context).let { if (it.contains(RING_BEFORE_TEST)) it.getBoolean(RING_BEFORE_TEST, false) else null }
    fun setRingBeforeTest(context: Context, value: Boolean?) {
        prefs(context).edit().apply { if (value == null) remove(RING_BEFORE_TEST) else putBoolean(RING_BEFORE_TEST, value) }.apply()
    }

    /**
     * Abandons the running test once it is past [ExperimentEndpoint.MAX_RUN_MS], so a test whose
     * finish keeps failing (Ring dead or out of range) can't keep monitoring on forever. Abandoning
     * rather than finishing from the latest reading: an automatic end point could be a stale widget
     * value and bias the result, while an abandoned run simply isn't counted. Only ever turns Ring
     * monitoring off, never on, and reads nothing. Call off the main thread.
     */
    fun abandonOverdueTest(context: Context, store: BatteryStore, nowMs: Long) {
        synchronized(experimentLock) {
            val e = store.activeExperiment() ?: return
            if (!ExperimentEndpoint.pastCap(e.startMs, nowMs)) return
            // Only the test checked above, and its monitoring cleanup only if it was the one removed.
            if (!store.abandonExperiment(e.id, "Ran past ${ExperimentEndpoint.MAX_RUN_MS / 3_600_000L} hours without a finish")) return
            if (!ExperimentEndpoint.monitoringAfterAbandon(e.mode, ringEnabled(context), ringBeforeTest(context), byUser = false))
                prefs(context).edit().putBoolean(RING_ENABLED, false).apply()
            setRingBeforeTest(context, null)
        }
    }

    /**
     * Held while a test is started, finished or abandoned together with its monitoring settings,
     * so the background abandon and the screen's buttons never interleave.
     */
    val experimentLock = Any()

    fun setRingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(RING_ENABLED, enabled).apply()
        schedule(context)
    }

    /**
     * After a restore: if it brought in a running test other than the monitoring arm, periodic
     * Ring reads go off, as starting that test would have done. Call off the main thread.
     * Holds [experimentLock] so abandoning the restored test can't fall between the read and
     * the write and have its monitoring setting undone (Neon deep review, Nov 26, 2026, 2.P2c-1).
     */
    fun afterImport(context: Context, store: BatteryStore) = synchronized(experimentLock) {
        val on = ringEnabled(context)
        if (on && !ExperimentEndpoint.monitoringAfterImport(on, store.activeExperiment()?.mode))
            prefs(context).edit().putBoolean(RING_ENABLED, false).apply()
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

/**
 * Which run of the job is current. Android can stop one run and start the next on the same
 * service before the first run's background work returns; that late work checks [live] so it
 * can't finish the new run or start a second Ring read.
 */
internal class JobRuns {
    // Atomic so a start and a stop on different threads can't lose a count and revive a stopped run.
    private val current = java.util.concurrent.atomic.AtomicInteger(0)
    fun start(): Int = current.incrementAndGet()
    fun stop() { current.incrementAndGet() }
    fun live(run: Int) = run == current.get()
}

class BatteryPollService : JobService() {
    private var reader: RingReader? = null
    private val runs = JobRuns()

    /**
     * Runs on the main thread, so it only does what must happen there: render Samsung's widget
     * offscreen. Saving readings and the backup happen on [Background.io]; the job finishes
     * once that work (and the optional Ring read) is done.
     */
    override fun onStartJob(params: JobParameters): Boolean {
        val run = runs.start()
        PowerConnectionReceiver.ensureRegistered(this)
        val store = BatteryStore.get(this)
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val startedCpu = android.os.Process.getElapsedCpuTime()
        val snapshot = try { SamsungWidgetSource.snapshot(this, includeIcons = false) to null } catch (e: Exception) { null to e }
        Background.io.execute {
            try {
                // A failed restore import must not skip the overdue check before the Ring decision.
                try { CloudBackupAgent.importPending(this) }
                catch (e: Exception) { store.logProbe("backup", "Restore import failed: ${e.javaClass.simpleName}") }
                try { PollScheduler.abandonOverdueTest(this, store, System.currentTimeMillis()) }
                catch (e: Exception) { store.logProbe("experiment", "Overdue test check failed: ${e.javaClass.simpleName}") }
                logStandbyBucketChange(store)
                PhoneBattery.read(this, "job")?.let(store::append)
                snapshot.first?.let { SamsungWidgetSource.record(this, store, it) }
                snapshot.second?.let { store.logProbe(SamsungWidgetSource.SOURCE, "Widget read failed: ${it.javaClass.simpleName}: ${it.message}") }
                // Database-only analysis: update journal, score/issue forecasts, refresh capabilities daily.
                ContextWatcher.check(this, store)
                ForecastKeeper.tick(this, store, listOfNotNull("phone", store.primaryWatchId(), BatteryStore.RING_ID))
                refreshCapabilitiesIfDue(store)
                // Phone-side only: writes the backup file (at most every 12 h); the Drive app uploads it.
                if (runs.live(run)) DriveBackup.maybeBackup(this, store)
            } catch (e: Throwable) {
                // Throwable, not Exception: an Error here would kill the single io thread's process.
                try { store.logProbe("job", "Background work failed: ${e.javaClass.simpleName}") } catch (_: Throwable) { }
            }
            try {
                Overhead.logPoll(this, store, android.os.SystemClock.elapsedRealtime() - startedMs,
                    android.os.Process.getElapsedCpuTime() - startedCpu)
            } catch (_: Throwable) { }
            // Read here, off the main thread. A running test other than the monitoring arm must
            // not wake the Ring; if the test can't be read, don't risk it.
            val ringAllowed = try {
                ExperimentEndpoint.backgroundReadAllowed(PollScheduler.ringEnabled(this),
                    PollScheduler.ringAddress(this) != null, store.activeExperiment()?.mode)
            } catch (_: Throwable) { false }
            Background.onMain { readRingOrFinish(params, store, ringAllowed, run) }
        }
        return true
    }

    private fun readRingOrFinish(params: JobParameters, store: BatteryStore, ringAllowed: Boolean, run: Int) {
        if (!runs.live(run)) return
        val address = PollScheduler.ringAddress(this)
        if (!ringAllowed || !PollScheduler.ringEnabled(this) || address == null) {
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
                Background.onMain { if (runs.live(run)) jobFinished(params, false) }
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
        runs.stop()
        reader?.cancel()
        reader = null
        return false
    }
}
