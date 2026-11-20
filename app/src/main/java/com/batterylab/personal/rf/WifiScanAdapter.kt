package com.batterylab.personal.rf

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import com.batterylab.rf.Status
import com.batterylab.rf.WifiHit
import com.batterylab.rf.WifiScanPort

/**
 * The Android side of wifi_scan (R26), used only by "Everything nearby". It asks for a scan with
 * WifiManager.startScan, listens for scan-results broadcasts only between [watchResults] and
 * [unwatchResults] (a session's start and stop), and reads each access point's address, network
 * name, level, frequency and timestamp. It never reads the connected network's details.
 * This file and BluetoothWatch.kt are the only RF files that register a receiver.
 */
class WifiScanAdapter(context: Context) : WifiScanPort {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private var receiver: BroadcastReceiver? = null

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun blocker(): String? = when {
        Build.VERSION.SDK_INT < 31 -> Status.UNSUPPORTED
        wifi == null -> Status.UNSUPPORTED
        !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> Status.PRECISE_NEEDED
        else -> null
    }

    override fun locationOn() = app.getSystemService(LocationManager::class.java)?.isLocationEnabled != false

    @Suppress("DEPRECATION")
    override fun requestScan(): Boolean = wifi?.startScan() == true

    override fun watchResults(listener: (updated: Boolean) -> Unit) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) =
                listener(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(r, filter)
        receiver = r
    }

    override fun unwatchResults() {
        val r = receiver ?: return
        receiver = null
        try { app.unregisterReceiver(r) } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    override fun results(): List<WifiHit> = (wifi?.scanResults ?: emptyList()).mapNotNull { r ->
        val bssid = r.BSSID ?: return@mapNotNull null
        // ScanResult.timestamp is microseconds since boot.
        WifiHit(bssid, r.SSID?.ifEmpty { null }, r.level, r.frequency, r.timestamp * 1_000)
    }
}
