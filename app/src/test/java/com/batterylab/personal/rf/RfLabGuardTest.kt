package com.batterylab.personal.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source checks for RF Lab in the app. T9 (isolation and forbidden calls), T10 (the manifest
 * permission list, backups), R12 (no background pieces) and R18 (two probe lines).
 */
class RfLabGuardTest {
    private val rfDir = File("src/main/java/com/batterylab/personal/rf")
    private val appDir = File("src/main/java/com/batterylab/personal")

    /** Source text without comments, so a comment naming a forbidden call does not count. */
    private fun code(f: File): String {
        val src = f.readText()
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i++ } else if (c == '"') inString = false
                }
                c == '"' -> { inString = true; out.append(c) }
                src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++; continue }
                src.startsWith("/*", i) -> { val end = src.indexOf("*/", i + 2); i = if (end < 0) src.length else end + 2; out.append(' '); continue }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private val rfSources by lazy {
        rfDir.listFiles()!!.filter { it.extension == "kt" }.also { assertTrue("found the RF sources", it.size >= 3) }
    }

    private fun manifest() = File("src/main/AndroidManifest.xml").readText()

    @Test fun manifestPermissionsArePrAPlusTheScanPermissions() {
        val perms = Regex("""<uses-permission\s+android:name="([^"]+)"([^>]*)/>""").findAll(manifest())
            .map { (it.groupValues[1] + " " + it.groupValues[2]).replace(Regex("\\s+"), " ").trim() }.toList()
        assertEquals(listOf(
            "android.permission.BLUETOOTH android:maxSdkVersion=\"30\"",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.BIND_APPWIDGET tools:ignore=\"ProtectedPermissions\"",
            "android.permission.PACKAGE_USAGE_STATS tools:ignore=\"ProtectedPermissions\"",
        ), perms)
        val text = manifest()
        assertFalse(text.contains("android.permission.INTERNET"))
        // R28: BLUETOOTH_SCAN with no usesPermissionFlags, and no background location.
        assertFalse(text.contains("usesPermissionFlags"))
        assertFalse(text.contains("neverForLocation"))
        assertFalse(text.contains("ACCESS_BACKGROUND_LOCATION"))
        assertFalse(text.contains("NEARBY_WIFI_DEVICES"))
        assertFalse(text.contains("FOREGROUND_SERVICE"))
    }

    @Test fun rfLabIsOneActivityAndNotExported() {
        val activities = Regex("""<activity android:name="([^"]+)" android:exported="(\w+)"""").findAll(manifest())
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf(".MainActivity" to "true", ".StatsActivity" to "false", ".RangeExportActivity" to "false",
            ".GlossaryActivity" to "false", ".rf.RfLabActivity" to "false"), activities)
        assertFalse(manifest().contains(".rf.") && Regex("""<(service|receiver|provider)[^>]*\.rf\.""").containsMatchIn(manifest()))
    }

    @Test fun backupRulesAreUnchangedAndNeverIncludeRf() {
        assertEquals("""<?xml version="1.0" encoding="utf-8"?>
<!-- Android 11 and older. Only the compressed history snapshot; see CloudBackupAgent. -->
<full-backup-content>
    <include domain="file" path="cloud/history.csv.gz" />
</full-backup-content>
""", File("src/main/res/xml/backup_rules.xml").readText())
        assertEquals("""<?xml version="1.0" encoding="utf-8"?>
<!-- Android 12 and newer. Only the compressed history snapshot; see CloudBackupAgent. -->
<data-extraction-rules>
    <cloud-backup>
        <include domain="file" path="cloud/history.csv.gz" />
    </cloud-backup>
    <device-transfer>
        <include domain="file" path="cloud/history.csv.gz" />
    </device-transfer>
</data-extraction-rules>
""", File("src/main/res/xml/backup_rules_31.xml").readText())
        for (name in listOf("DriveBackup.kt", "CloudBackupAgent.kt")) {
            val text = File(appDir, name).readText()
            assertFalse(name, Regex("""["/]rf["/]""").containsMatchIn(text))
            assertFalse(name, text.contains("com.batterylab.rf") || text.contains("RfRuntime") || text.contains("personal.rf"))
        }
        // The session folder is files/rf, outside the backed-up cloud/ folder.
        assertTrue(code(File(rfDir, "RfRuntime.kt")).contains("File(app.filesDir, \"rf\")"))
    }

    @Test fun theRfPackageContainsNoSamsungPackageReference() {
        rfDir.walkTopDown().filter { it.isFile }.forEach { file ->
            assertFalse(file.name, file.readText().contains("com.samsung"))
        }
    }

    @Test fun theRfPackageMakesNoForbiddenCall() {
        val forbidden = listOf(
            // R5: no connection info and no location info.
            "getConnectionInfo", "connectionInfo", "FLAG_INCLUDE_LOCATION_INFO",
            // R6: no GATT discovery, tuning, notifications, bonding or characteristic access.
            "discoverServices", "requestConnectionPriority", "requestMtu", "setPreferredPhy",
            "setCharacteristicNotification", "createBond", "readCharacteristic", "writeCharacteristic",
            "readDescriptor", "writeDescriptor",
            // T9: nothing that asks for a location fix.
            "requestLocationUpdates", "requestSingleUpdate", "getLastKnownLocation", "getCurrentLocation",
            "com.google.android.gms.location", "FusedLocation",
            // R12: no background collection.
            "JobScheduler", "AlarmManager", "startForeground", "Service()", "WorkManager",
            // R17: never the paired name of a device, and never pairs.
            "getName", ".name ==", "device.name", "getAlias", "setPairingConfirmation",
        )
        // R17, R26: advertised names and payloads only in the two scan adapters; network names only in Wi-Fi.
        val namesOk = setOf("BleScanAdapter.kt", "WifiScanAdapter.kt")
        for (f in rfSources) {
            val text = code(f)
            for (word in forbidden) assertFalse("${f.name} uses $word", text.contains(word))
            if (f.name !in namesOk) for (word in listOf("deviceName", "scanRecord", "getScanRecord", "manufacturerSpecificData"))
                assertFalse("${f.name} uses $word", text.contains(word))
            if (f.name != "WifiScanAdapter.kt") for (word in listOf("SSID", "getSsid", "wifiSsid", "BSSID", "scanResults",
                "getScanResults")) assertFalse("${f.name} uses $word", text.contains(word))
            // LocationManager only for isLocationEnabled.
            Regex("""LocationManager[^\n]*""").findAll(text.lines().filterNot { it.startsWith("import ") }.joinToString("\n")).forEach {
                assertTrue("${f.name}: ${it.value}", it.value.contains("isLocationEnabled") || it.value.contains("LOCATION_SERVICE"))
            }
            // connectGatt only in the ring_link adapter; scanning only in the scan adapter.
            if (f.name != "RingLinkAdapter.kt") assertFalse("${f.name} calls connectGatt", text.contains("connectGatt"))
            if (f.name !in namesOk) assertFalse("${f.name} starts a scan", text.contains("startScan"))
            // R12: the receivers are the session-only Bluetooth on/off watch and Wi-Fi results watch.
            if (f.name != "BluetoothWatch.kt" && f.name != "WifiScanAdapter.kt") {
                assertFalse("${f.name} registers a receiver", text.contains("registerReceiver"))
                assertFalse("${f.name} has a receiver", text.contains("BroadcastReceiver"))
            }
        }
        // The ring_link adapter makes only the four allowed GATT calls.
        val ring = code(File(rfDir, "RingLinkAdapter.kt"))
        val gattCalls = Regex("""\bg(att)?\??\.(\w+)\(""").findAll(ring).map { it.groupValues[2] }.toSet()
        assertEquals(setOf("disconnect", "close", "readRemoteRssi"), gattCalls)
        assertTrue(ring.contains("connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h)"))
        // Each receiver is unregistered by the same class that registers it.
        assertTrue(code(File(rfDir, "BluetoothWatch.kt")).contains("unregisterReceiver"))
        val wifiScan = code(File(rfDir, "WifiScanAdapter.kt"))
        assertTrue(wifiScan.contains("unregisterReceiver"))
        // The Wi-Fi scan reads results only: no connecting, no saved networks, no Wi-Fi toggling.
        for (word in listOf("addNetwork", "enableNetwork", "setWifiEnabled", "configuredNetworks", "reconnect", "disconnect"))
            assertFalse("WifiScanAdapter uses $word", wifiScan.contains(word))
        // createBond nowhere in the app.
        appDir.walkTopDown().filter { it.extension == "kt" }.forEach { assertFalse(it.name, code(it).contains("createBond")) }
    }

    @Test fun wifiLinkUsesOnlyTheNetworkStatePath() {
        val text = code(File(rfDir, "WifiLinkStream.kt"))
        for (needed in listOf("registerNetworkCallback", "TRANSPORT_WIFI", "getNetworkCapabilities", "transportInfo",
            "signalStrength", "unregisterNetworkCallback")) assertTrue(needed, text.contains(needed))
        assertFalse(text.contains("WifiManager"))
    }

    @Test fun phoneGuideUsesItsOwnPreferencesAndItsRulesStayPure() {
        val activity = code(File(rfDir, "RfLabActivity.kt"))
        val files = Regex("""getSharedPreferences\(([^,]+),""").findAll(activity)
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf("PREFS", "\"rf_guide\""), files)
        val rules = code(File(rfDir, "GuideSteps.kt"))
        assertFalse(rules.contains("import android."))
        for (name in listOf("GuideSteps.kt", "GuideHistory.kt")) {
            val text = code(File(rfDir, name))
            for (forbidden in listOf("BatteryStore", "requestPermissions", "getSharedPreferences", "rf.start(", "rf.stop("))
                assertFalse("$name uses $forbidden", text.contains(forbidden))
        }
    }

    @Test fun exactlyTwoProbeLinesAndTheSchemaStaysAt7() {
        val calls = rfSources.flatMap { f -> Regex("""logProbe\(([^,]*),""").findAll(code(f)).map { it.groupValues[1].trim() }.toList() }
        assertEquals(listOf("\"rf\"", "\"rf\""), calls)
        assertTrue(rfSources.none { code(it).contains("writableDatabase") || code(it).contains("SQLite") })
        assertTrue(File(appDir, "BatteryStore.kt").readText().contains("\"battery_lab.db\", null, 7)"))
    }
}
