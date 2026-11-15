package com.batterylab.personal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Records the phone the moment it's plugged in or unplugged, plus a second read 3.5 s after
 * plugging in once fast-charging negotiation has settled. Android won't deliver these events to an
 * app that isn't running, so this only works while Battery Lab's process is alive (after a check
 * or while the app is open); the 15-minute checks still record plug type either way.
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> true
            Intent.ACTION_POWER_DISCONNECTED -> false
            else -> return
        }
        val app = context.applicationContext
        val pending = goAsync()
        Background.io.execute {
            try { record(app, if (connected) "plug" else "unplug") } finally { pending.finish() }
        }
        if (connected) Background.onMainDelayed(3_500) {
            Background.io.execute { record(app, "plug_settled") }
        }
    }

    private fun record(context: Context, trigger: String) {
        try {
            PhoneBattery.read(context, trigger)?.let(BatteryStore.get(context)::append)
        } catch (e: Exception) {
            BatteryStore.get(context).logProbe("power", "Plug read failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        @Volatile private var registered = false

        /** Registers once per process on the application context; safe to call often. */
        fun ensureRegistered(context: Context) {
            if (registered) return
            synchronized(this) {
                if (registered) return
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
                }
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    context.applicationContext.registerReceiver(PowerConnectionReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
                else context.applicationContext.registerReceiver(PowerConnectionReceiver(), filter)
                registered = true
            }
        }
    }
}
