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
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.ConcurrentHashMap

/** Samples locally, then sends an update via the Wear OS Data Layer. No phone polling. */
object WatchReporter {
    private const val JOB_ID = 44008
    /**
     * Every 2 hours. The watch app only cross-checks Samsung's widget, and about 12 samples a day
     * (roughly 8 that land within 5 minutes of a widget read) is plenty for that; every 30 minutes
     * cost the watch four times the wakeups and Bluetooth syncs for no better answer.
     */
    private const val INTERVAL_MS = 2 * 60L * 60L * 1000L
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        // Rescheduling restarts the 2-hour clock, so opening the app often would delay every read.
        if (scheduler.getPendingJob(JOB_ID)?.intervalMillis == INTERVAL_MS) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, WatchBatteryJob::class.java))
                .setPeriodic(INTERVAL_MS)
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

    fun send(context: Context, onFinished: (Boolean) -> Unit) {
        val completion = JobFinishGate()
        fun finish(success: Boolean) {
            completion.finish { onFinished(success) }
        }
        val percent = batteryPercent(context)
        if (percent == null) {
            finish(false)
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
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { finish(true) }
            .addOnFailureListener { finish(false) }
            .addOnCanceledListener { finish(false) }
    }
}

class WatchBatteryJob : JobService() {
    private val jobs = ConcurrentHashMap<Int, JobFinishGate>()

    override fun onStartJob(params: JobParameters): Boolean {
        val gate = JobFinishGate()
        jobs.put(params.jobId, gate)?.stop()
        WatchReporter.send(this) {
            gate.finish { jobFinished(params, false) }
            jobs.remove(params.jobId, gate)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.stop()
        return false
    }
}
