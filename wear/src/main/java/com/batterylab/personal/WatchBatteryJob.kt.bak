package com.batterylab.personal

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** How a send ended. Only [FAILED] is sure that nothing reached the Data Layer. */
enum class SendOutcome {
    SENT,
    /** No reading was taken, or the Data Layer refused the item: nothing was stored, so a retry adds no copy. */
    FAILED,
    /** The send was cancelled: the item may still be stored, so it is not retried (a retry could add a second sample). */
    CANCELLED,
}

/** Samples locally, then sends an update via the Wear OS Data Layer. No phone polling. */
object WatchReporter {
    private const val JOB_ID = 44008
    /**
     * Every 2 hours. The watch app only cross-checks Samsung's widget, and about 12 samples a day
     * (roughly 8 that land within 5 minutes of a widget read) is plenty for that; every 30 minutes
     * cost the watch four times the wakeups and Bluetooth syncs for no better answer.
     */
    internal const val INTERVAL_MS = 2 * 60L * 60L * 1000L
    /**
     * The job runs in the last 15 minutes of each 2-hour period, so samples land about 1 h 45 min
     * to 2 h 15 min apart. Without a flex the system may run it anywhere in the period, leaving gaps
     * from almost nothing to almost 4 hours (Neon deep review, Nov 26, 2026). 15 minutes is above
     * the system minimum (5 percent of the period, 6 minutes here).
     */
    internal const val FLEX_MS = 15 * 60L * 1000L
    /**
     * A failed sample is retried after 15, 30, then 60 minutes (exponential), so at most about three
     * extra wakeups fit before the next regular run even if the Data Layer keeps failing.
     */
    internal const val BACKOFF_MS = 15 * 60L * 1000L

    /** True when the pending job already has this timing; rescheduling would restart its clock. */
    internal fun isCurrent(intervalMs: Long, flexMs: Long, backoffMs: Long, backoffPolicy: Int): Boolean =
        intervalMs == INTERVAL_MS && flexMs == FLEX_MS && backoffMs == BACKOFF_MS &&
            backoffPolicy == JobInfo.BACKOFF_POLICY_EXPONENTIAL

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        // Rescheduling restarts the 2-hour clock, so opening the app often would delay every read.
        // A job saved by an older version (no flex, default backoff) is replaced once.
        val pending = scheduler.getPendingJob(JOB_ID)
        if (pending != null &&
            isCurrent(pending.intervalMillis, pending.flexMillis, pending.initialBackoffMillis, pending.backoffPolicy)) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, WatchBatteryJob::class.java))
                .setPeriodic(INTERVAL_MS, FLEX_MS)
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build())
    }

    fun batteryPercent(context: Context): Int? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent in 0..100) return percent
        val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) (100.0 * level / scale).toInt().coerceIn(0, 100) else null
    }

    /** [urgent] is for a sample the user asked for: Wear OS may hold a normal item up to 30 minutes
     *  before syncing it (Neon deep review, Nov 26, 2026). The 2-hour job stays non-urgent to save battery. */
    fun send(context: Context, urgent: Boolean = false, onFinished: (SendOutcome) -> Unit) {
        val completion = JobFinishGate()
        fun finish(outcome: SendOutcome) {
            completion.finish { onFinished(outcome) }
        }
        val percent = batteryPercent(context)
        if (percent == null) {
            finish(SendOutcome.FAILED)
            return
        }
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val state = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        // Plugged in counts as on the charger, as on the phone: a charge hold is not drain.
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL ||
            (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
        // One item per sample: with a single shared path, every sample taken while the phone was
        // out of range overwrote the last, and only the newest ever reached it.
        val now = System.currentTimeMillis()
        val request = PutDataMapRequest.create("/batterylab/watch/$now").apply {
            dataMap.putLong("time_ms", now)
            dataMap.putInt("percent", percent)
            dataMap.putBoolean("charging", charging)
            dataMap.putString("model", Build.MODEL)
        }.asPutDataRequest().apply { if (urgent) setUrgent() }
        finishWhenDone(Wearable.getDataClient(context).putDataItem(request), context.mainExecutor, ::finish)
    }

    /** Reports the send's outcome once. */
    internal fun finishWhenDone(task: Task<*>, executor: Executor, finish: (SendOutcome) -> Unit) {
        task.addOnSuccessListener(executor) { finish(SendOutcome.SENT) }
            .addOnFailureListener(executor) { finish(SendOutcome.FAILED) }
            .addOnCanceledListener(executor) { finish(SendOutcome.CANCELLED) }
    }

    /**
     * Whether the job asks to run again soon. Only a sure failure is retried: a cancelled send may
     * still land, and a job the system stopped is left alone ([WatchBatteryJob.onStopJob]), since its
     * item may still be written too (Neon deep review, Nov 26, 2026).
     */
    internal fun needsRetry(outcome: SendOutcome): Boolean = outcome == SendOutcome.FAILED
}

class WatchBatteryJob : JobService() {
    private val jobs = ConcurrentHashMap<Int, JobFinishGate>()

    override fun onStartJob(params: JobParameters): Boolean {
        val gate = JobFinishGate()
        jobs.put(params.jobId, gate)?.stop()
        WatchReporter.send(this) { outcome ->
            try {
                gate.finish { jobFinished(params, WatchReporter.needsRetry(outcome)) }
            } finally {
                jobs.remove(params.jobId, gate)
            }
        }
        return true
    }
    /** A stopped send is not retried: its item may still be written, and a retry would add a second sample. */
    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.stop()
        return false
    }
}
