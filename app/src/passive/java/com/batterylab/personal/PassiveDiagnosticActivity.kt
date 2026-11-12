package com.batterylab.personal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Passive compatibility probe: no connectGatt, Bluetooth scan, polling, reflection,
 * foreground service, wearable app, network access, or data uploads.
 *
 * The receiver reads only battery broadcasts Android has *already* emitted.
 */
class PassiveDiagnosticActivity : Activity() {
    private val action = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private val requestCode = 41
    private var liveReceiverRegistered = false
    private lateinit var root: LinearLayout
    private val liveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            PassiveBatteryRecords.receive(context, intent, "foreground")
            showState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(14, 24, 40)
        window.navigationBarColor = Color.rgb(14, 24, 40)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 32)
            setBackgroundColor(Color.rgb(14, 24, 40))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        if (!hasBluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), requestCode)
            }
        }
        showState()
    }

    override fun onStart() {
        super.onStart()
        if (hasBluetoothPermission() && !liveReceiverRegistered) {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(liveReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(liveReceiver, filter)
            }
            liveReceiverRegistered = true
        }
        if (::root.isInitialized) showState()
    }

    override fun onStop() {
        if (liveReceiverRegistered) {
            unregisterReceiver(liveReceiver)
            liveReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) {
            if (hasBluetoothPermission() && !liveReceiverRegistered) {
                val filter = IntentFilter(action)
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(liveReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(liveReceiver, filter)
                }
                liveReceiverRegistered = true
            }
            showState()
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun text(value: String, size: Float = 15f, emphasis: Boolean = false) {
        root.addView(TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(if (emphasis) Color.WHITE else Color.rgb(196, 215, 235))
            setPadding(0, 7, 0, 9)
        })
    }

    private fun button(title: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun pairedNames(): String {
        if (!hasBluetoothPermission()) return "Nearby Devices permission required"
        return try {
            @Suppress("DEPRECATION")
            val devices = BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty()
            if (devices.isEmpty()) "No paired devices listed (not conclusive)."
            else devices.sortedBy { it.name ?: "" }.joinToString("\n") { device ->
                val name = device.name ?: "Unnamed device"
                val id = PassiveBatteryRecords.mask(device.address)
                "• $name ($id)"
            }
        } catch (error: SecurityException) {
            "Permission blocked device listing."
        } catch (error: Exception) {
            "Unable to list paired devices: ${error.javaClass.simpleName}"
        }
    }

    private fun phoneBattery(): String {
        val state = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = state?.getIntExtra("level", -1) ?: -1
        val scale = state?.getIntExtra("scale", 100) ?: 100
        return if (level >= 0 && scale > 0) "${level * 100 / scale}% (Android phone API)"
            else "Unavailable"
    }

    private fun showState() {
        if (!::root.isInitialized) return
        root.removeAllViews()
        text("Battery Lab", 28f, true)
        text("PASSIVE COMPATIBILITY DIAGNOSTIC · v0.2", 13f, true)
        text("This version never initiates a Bluetooth connection or asks a wearable for its battery. No watch app or special setup is needed.")
        text("Phone battery: ${phoneBattery()}", 18f, true)
        text("Bluetooth permission: ${if (hasBluetoothPermission()) "Granted" else "Not granted"}")
        text("Live listener: ${if (liveReceiverRegistered) "Running while this screen is open" else "Not active"}")
        text("A manifest receiver also attempts to record updates when the app is closed. Android/Samsung may restrict that; background reception is NOT yet verified.")
        if (!hasBluetoothPermission() && Build.VERSION.SDK_INT >= 31) {
            button("Grant Nearby Devices permission") {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), requestCode)
            }
        }
        text("OBSERVED BLUETOOTH BATTERY EVENTS", 18f, true)
        val events = PassiveBatteryRecords.all(this)
        text("${events.length()} events stored. They may be from headphones or other devices; no Watch/Ring reading is presumed.")
        if (events.length() == 0) {
            text("None received yet. Keep this screen open while Samsung updates a battery value. No events does NOT prove the Ring/Watch data is inaccessible.")
        } else {
            for (i in events.length() - 1 downTo maxOf(0, events.length() - 50)) {
                val e = events.optJSONObject(i) ?: continue
                text("${PassiveBatteryRecords.time(e.optLong("time"))} · ${e.optString("name")} (${e.optString("id")})\n" +
                    "${e.optInt("percent", -1).let { if (it >= 0) "$it%" else "Unknown" }} · ${e.optString("source")}")
            }
        }
        button("Refresh") { showState() }
        button("Share diagnostic report") {
            val data = PassiveBatteryRecords.export(this)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Battery Lab passive diagnostic")
                putExtra(Intent.EXTRA_TEXT, data)
            }, "Share diagnostic report"))
        }
        button("Clear stored observations") {
            AlertDialog.Builder(this)
                .setMessage("Delete all passive battery observations on this phone?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    PassiveBatteryRecords.clear(this)
                    showState()
                }.show()
        }
        text("PAIRED DEVICES (METADATA ONLY)", 18f, true)
        text(pairedNames())
        text("Device names and shortened identifier hashes may appear in shared reports. Review before sharing. This screen never opens Bluetooth connections.", 12f)
    }
}

/** Manifest receiver is separate from the activity to check background delivery. */
class PassiveBatteryBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PassiveBatteryRecords.receive(context, intent, "manifest")
    }
}

internal object PassiveBatteryRecords {
    private const val action = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val deviceExtra = "android.bluetooth.device.extra.DEVICE"
    private const val levelExtra = "android.bluetooth.device.extra.BATTERY_LEVEL"
    private const val pref = "passive_battery_diagnostic"
    private const val key = "events_json"

    fun mask(address: String?): String {
        if (address.isNullOrBlank()) return "unknown"
        val bytes = MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8))
        return bytes.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Synchronized fun receive(context: Context, intent: Intent, source: String) {
        if (intent.action != action) return
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return
        val device = try {
            if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(deviceExtra, BluetoothDevice::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<BluetoothDevice>(deviceExtra)
            }
        } catch (_: Exception) { null }
        val name = try { device?.name ?: "Unknown Bluetooth device" }
            catch (_: SecurityException) { "Unknown Bluetooth device" }
        val id = try { mask(device?.address) }
            catch (_: SecurityException) { "unknown" }
        val level = intent.getIntExtra(levelExtra, -1)
        if (level !in 0..100 && level != -1 && level != -100) return
        val now = System.currentTimeMillis()
        val events = all(context)
        val last = events.optJSONObject(events.length() - 1)
        if (last != null && last.optString("id") == id && last.optInt("percent") == level &&
            now - last.optLong("time") < 10_000L) return // receiver can run twice
        events.put(JSONObject().put("time", now).put("id", id).put("name", name)
            .put("percent", level).put("source", source))
        val capped = JSONArray()
        for (i in maxOf(0, events.length() - 400) until events.length()) capped.put(events.get(i))
        context.getSharedPreferences(pref, Context.MODE_PRIVATE).edit().putString(key, capped.toString()).apply()
    }

    fun all(context: Context): JSONArray = try {
        JSONArray(context.getSharedPreferences(pref, Context.MODE_PRIVATE).getString(key, "[]"))
    } catch (_: Exception) { JSONArray() }

    fun clear(context: Context) {
        context.getSharedPreferences(pref, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    fun time(value: Long): String = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date(value))

    fun export(context: Context): String = buildString {
        appendLine("Battery Lab passive compatibility report")
        appendLine("Export: ${time(System.currentTimeMillis())}")
        appendLine("Phone model: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}")
        appendLine("No active Bluetooth reads, scanning, connection, or reflection used")
        appendLine("time,device_name,device_id_hash,battery_percent,source")
        val events = all(context)
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val name = e.optString("name").replace('"', '\'').replace(',', ' ')
            appendLine("${time(e.optLong("time"))},$name,${e.optString("id")},${e.optInt("percent")},${e.optString("source")}")
        }
    }
}
