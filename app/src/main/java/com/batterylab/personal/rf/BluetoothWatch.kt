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

    fun start(handler: Handler?, listener: (Boolean) -> Unit) = start(handler, false, listener)

    fun start(handler: Handler?, receiveSystemBluetooth: Boolean, listener: (Boolean) -> Unit) {
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
        // Bluetooth broadcasts can come from a privileged UID rather than the system UID.
        // ACTION_STATE_CHANGED is protected by Android. Existing stream callers keep their flags.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, null, handler,
            if (receiveSystemBluetooth) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(r, filter, null, handler)
        receiver = r
    }

    fun stop() {
        val r = receiver ?: return
        receiver = null
        try { context.unregisterReceiver(r) } catch (_: Exception) { }
    }
}
