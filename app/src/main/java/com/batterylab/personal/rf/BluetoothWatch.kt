package com.batterylab.personal.rf

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler

/**
 * Bluetooth on/off changes for an RF Lab stream or the visible phone test guide. The receiver
 * exists only between [start] and [stop]. The guide stops listening when its screen closes.
 * This is the only file in the RF package that registers a receiver.
 */
class BluetoothWatch(private val context: Context) {
    private var receiver: BroadcastReceiver? = null

    fun start(handler: Handler?, listener: (Boolean) -> Unit) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> listener(true)
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> listener(false)
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        // Bluetooth broadcasts can come from a privileged UID rather than the system UID, which a
        // not-exported receiver would miss. ACTION_STATE_CHANGED is a protected broadcast that only
        // the system can send, so exporting this receiver lets no app spoof it.
        // Always exported: a not-exported receiver silently misses these broadcasts on API 33+.
        val flags = receiverFlags(Build.VERSION.SDK_INT)
        if (flags != null) context.registerReceiver(r, filter, null, handler, flags)
        else context.registerReceiver(r, filter, null, handler)
        receiver = r
    }

    fun stop() {
        val r = receiver ?: return
        receiver = null
        try { context.unregisterReceiver(r) } catch (_: Exception) { }
    }

    internal companion object {
        /** The registerReceiver flag on [sdk]: exported on API 33 and later, none before (no flags there). */
        fun receiverFlags(sdk: Int): Int? = if (sdk >= 33) Context.RECEIVER_EXPORTED else null
    }
}
