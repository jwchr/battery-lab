package com.batterylab.personal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

// Throwaway: proves PR CI fails on a new lint error. Reverted in the next commit.
object LintProbe {
    fun listen(context: Context) {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {}
        }, IntentFilter("com.batterylab.personal.PROBE"))
    }
}
