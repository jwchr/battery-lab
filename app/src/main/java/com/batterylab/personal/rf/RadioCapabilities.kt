package com.batterylab.personal.rf

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.ranging.RangingManager

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
        ) + ranging(
            Build.VERSION.SDK_INT,
            channelSounding = { if (Build.VERSION.SDK_INT >= 36) safe { pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING) } else false },
            rangingManager = { if (Build.VERSION.SDK_INT >= 36) safe { app.getSystemService(RangingManager::class.java) != null } else false },
        )
    }

    /** First Android version with Channel Sounding and the ranging service (Android 16, API 36). */
    const val RANGING_SDK = 36

    /**
     * Android 16+ distance features. Older phones answer "no" without asking, since the
     * feature names and the service don't exist there. Presence checks only: no session starts.
     */
    fun ranging(
        sdk: Int,
        channelSounding: () -> Boolean?,
        rangingManager: () -> Boolean?,
    ): List<Pair<String, Boolean?>> = listOf(
        "Bluetooth Channel Sounding (distance)" to (if (sdk >= RANGING_SDK) channelSounding() else false),
        "Android ranging service" to (if (sdk >= RANGING_SDK) rangingManager() else false),
    )
}
