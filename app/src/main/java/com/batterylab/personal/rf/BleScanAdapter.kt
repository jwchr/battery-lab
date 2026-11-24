package com.batterylab.personal.rf

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.batterylab.rf.BleScanPort
import com.batterylab.rf.ScanHit
import com.batterylab.rf.Status

/**
 * The Android side of ble_scan (R24): one unfiltered scan, started only inside a session. It
 * reads each result's address (kept in memory for matching and counting), signal and time. For
 * "Everything nearby" it also reads the name a device advertises and its first manufacturer id from
 * the advertisement (never the paired name, so no connection is needed). This and WifiScanAdapter.kt
 * are the only RF files that read names. Scanning needs Android 12 or later, Nearby devices and
 * Precise location.
 */
class BleScanAdapter(context: Context) : BleScanPort {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val watch = BluetoothWatch(app)
    private var callback: ScanCallback? = null

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun blocker(): String? = when {
        Build.VERSION.SDK_INT < 31 -> Status.UNSUPPORTED
        !granted(Manifest.permission.BLUETOOTH_SCAN) -> Status.PERMISSION_NEEDED
        !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> Status.PRECISE_NEEDED
        else -> null
    }

    override fun bluetoothOn() = manager?.adapter?.isEnabled == true

    override fun locationOn() = app.getSystemService(LocationManager::class.java)?.isLocationEnabled != false

    override fun startScan(onHit: (ScanHit) -> Unit, onFailed: (Int) -> Unit): Boolean {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .setLegacy(false)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onHit(hit(result))
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onHit(hit(it)) }
            override fun onScanFailed(errorCode: Int) = onFailed(errorCode)
        }
        callback = cb
        scanner.startScan(null, settings, cb)
        return true
    }

    private fun hit(r: ScanResult): ScanHit {
        val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - r.timestampNanos) / 1_000_000
        val record = r.scanRecord
        val vendors = record?.manufacturerSpecificData
        val vendor = if (vendors != null && vendors.size() > 0) vendors.keyAt(0) else null
        return ScanHit(r.device.address, r.rssi, r.timestampNanos, wall, r.isConnectable, record?.deviceName, vendor)
    }

    override fun stopScan() {
        val cb = callback ?: return
        callback = null
        try { manager?.adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) { }
    }

    override fun watchBluetooth(listener: (Boolean) -> Unit) = watch.start(null, receiveSystemBluetooth = true, listener = listener)
    override fun unwatchBluetooth() = watch.stop()
}
