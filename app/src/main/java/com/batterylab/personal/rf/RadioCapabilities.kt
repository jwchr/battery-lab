package com.batterylab.personal.rf

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build

/**
 * What this phone's radios support, for the read-only card in RF Lab (R31). Only yes/no feature
 * checks: nothing scans, connects or turns a radio on.
 */
object RadioCapabilities {
    fun list(context: Context): List<Pair<String, Boolean?>> {
        val app = context.applicationContext
        val bt = app.getSystemService(BluetoothManager::class.java)?.adapter
        val wifi = app.getSystemService(WifiManager::class.java)
        val pm = app.packageManager
        fun safe(f: () -> Boolean): Boolean? = try { f() } catch (_: Exception) { null }
        return listOf(
            "Bluetooth LE 2M speed" to bt?.let { safe { it.isLe2MPhySupported } },
            "Bluetooth LE long range (Coded)" to bt?.let { safe { it.isLeCodedPhySupported } },
            "Bluetooth extended advertising" to bt?.let { safe { it.isLeExtendedAdvertisingSupported } },
            "Wi-Fi 5 GHz" to wifi?.let { safe { it.is5GHzBandSupported } },
            "Wi-Fi 6 GHz" to (if (Build.VERSION.SDK_INT >= 30) wifi?.let { safe { it.is6GHzBandSupported } } else false),
            "Wi-Fi round-trip timing (RTT)" to safe { pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT) },
            "Wi-Fi Aware" to safe { pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) },
            "Ultra-wideband (UWB)" to (if (Build.VERSION.SDK_INT >= 31) safe { pm.hasSystemFeature(PackageManager.FEATURE_UWB) } else false),
        )
    }
}
