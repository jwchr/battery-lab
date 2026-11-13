package com.batterylab.personal

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

object PollScheduler {
    private const val JOB_ID = 44007
    private const val FIFTEEN_MINUTES = 15 * 60 * 1000L
    const val PREFS = "battery_settings"
    const val RING_ADDRESS = "ring_address"
    const val RING_ENABLED = "ring_monitoring_enabled"

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        // Periodic jobs are opportunistic. Sample timestamps reflect actual collection time.
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, BatteryPollService::class.java))
            .setPeriodic(FIFTEEN_MINUTES)
            .setPersisted(true)
            .build())
    }

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ringEnabled(context: Context) = prefs(context).getBoolean(RING_ENABLED, false)
    fun ringAddress(context: Context) = prefs(context).getString(RING_ADDRESS, null)

    /** Ring history id: the selected Ring's address when chosen, else a widget-only id. */
    fun ringDeviceId(context: Context) = ringAddress(context)?.let { "ring:$it" } ?: "ring:samsung_widget"
    fun setRingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(RING_ENABLED, enabled).apply()
        schedule(context)
    }
}

class BatteryPollService : JobService() {
    private var reader: RingReader? = null
    private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        val store = BatteryStore(this)
        PhoneBattery.read(this)?.let(store::append)
        // Zero-radio source: whatever Samsung's Battery widget currently shows (main thread).
        SamsungWidgetSource.sample(this, store)
        val address = PollScheduler.ringAddress(this)
        if (!PollScheduler.ringEnabled(this) || address == null) {
            store.close()
            return false
        }
        reader = RingReader(applicationContext, address) { reading, error ->
            if (reading != null) store.append(reading)
            if (error != null) store.logProbe("ring:$address", error)
            store.close()
            reader = null
            if (!stopped) jobFinished(params, false)
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        reader?.cancel()
        reader = null
        return false
    }
}
