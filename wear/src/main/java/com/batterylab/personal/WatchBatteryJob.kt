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

/** Samples locally, then sends an update via the Wear OS Data Layer. No phone polling. */
object WatchReporter {
    private const val JOB_ID = 44008
    private const val THIRTY_MINUTES = 30L * 60L * 1000L
    fun schedule(context: Context) {
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, WatchBatteryJob::class.java))
                .setPeriodic(THIRTY_MINUTES)
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
        val percent = batteryPercent(context)
        if (percent == null) {
            onFinished(false)
            return
        }
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val state = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
        val request = PutDataMapRequest.create("/batterylab/watch").apply {
            dataMap.putLong("time_ms", System.currentTimeMillis())
            dataMap.putInt("percent", percent)
            dataMap.putBoolean("charging", charging)
            dataMap.putString("model", Build.MODEL)
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { onFinished(true) }
            .addOnFailureListener { onFinished(false) }
    }
}

class WatchBatteryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        WatchReporter.send(this) { jobFinished(params, false) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = false
}
