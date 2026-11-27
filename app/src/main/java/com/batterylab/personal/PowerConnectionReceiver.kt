package com.batterylab.personal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

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
        PowerReceiverAsync.run(
            connected = connected,
            // Android 14+ can deliver cached broadcasts late. Do not label the current reading
            // with a plug state that is already known to be wrong.
            isCurrent = { PlugEvent.stillCurrent(connected, PhoneBattery.isPlugged(app)) },
            read = { trigger ->
                if (trigger != "plug_settled" || PhoneBattery.isPlugged(app) != false) record(app, trigger)
            },
            logLate = {
                try { BatteryStore.get(app).logProbe("power", "Skipped a late ${if (connected) "plug" else "unplug"} event") } catch (_: Throwable) { }
            },
            finish = { pending.finish() },
            postToIo = { Background.io.execute(it) },
            postDelayed = { delayMs, block -> Background.onMainDelayed(delayMs, block) },
            now = { SystemClock.elapsedRealtime() },
        )
    }

    private fun record(context: Context, trigger: String) {
        try {
            PhoneBattery.read(context, trigger)?.let(BatteryStore.get(context)::append)
        } catch (e: Throwable) {
            try { BatteryStore.get(context).logProbe("power", "Plug read failed: ${e.javaClass.simpleName}") } catch (_: Throwable) { }
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

/** Pure check, unit-tested: an event is current unless the charger state is known and disagrees. */
internal object PlugEvent {
    fun stillCurrent(connected: Boolean, pluggedNow: Boolean?) = pluggedNow == null || pluggedNow == connected
}

/** Plain-lambda broadcast lifetime, independent of Android so queued work and time are testable. */
internal object PowerReceiverAsync {
    private const val WAITING = 0
    private const val SETTLED_STARTED = 1
    private const val FINISHED = 2

    fun run(
        connected: Boolean,
        isCurrent: () -> Boolean,
        read: (String) -> Unit,
        logLate: () -> Unit,
        finish: () -> Unit,
        postToIo: (() -> Unit) -> Unit,
        postDelayed: (Long, () -> Unit) -> Unit,
        now: () -> Long,
    ) {
        val state = AtomicInteger(WAITING)
        fun finishOnce() {
            if (state.getAndSet(FINISHED) != FINISHED) finish()
        }
        fun guarded(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                finishOnce()
                throw failure
            }
        }

        guarded {
            val startedAt = now()
            if (!isCurrent()) {
                postToIo { try { logLate() } finally { finishOnce() } }
                return@guarded
            }
            if (connected) {
                // This callback runs on main, not behind a possibly busy I/O queue. Once the
                // settled read starts, its finally owns finishing after the save instead.
                postDelayed((8_000 - (now() - startedAt)).coerceAtLeast(0)) {
                    if (state.compareAndSet(WAITING, FINISHED)) finish()
                }
            }
            postToIo {
                guarded {
                    if (state.get() != FINISHED) read(if (connected) "plug" else "unplug")
                    if (!connected) finishOnce()
                }
            }
            if (connected && state.get() != FINISHED) {
                postDelayed((3_500 - (now() - startedAt)).coerceAtLeast(0)) {
                    guarded {
                        if (state.get() == WAITING) postToIo {
                            try {
                                // Check on the worker too: the delayed callback may have queued
                                // this read on time, but the single I/O thread can still be busy.
                                if (now() - startedAt < 8_000 && state.compareAndSet(WAITING, SETTLED_STARTED)) {
                                    read("plug_settled")
                                }
                            } finally { finishOnce() }
                        }
                    }
                }
            }
        }
    }
}
