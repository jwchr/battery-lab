package com.batterylab.personal

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Battery-only second-client BLE probe, based on RFC-SGR-001 (June 2026).
 * No gestures, health operations, firmware operations, or persistent GATT sessions.
 * This is experimental and must be validated on the owner's ring/firmware.
 */
class RingReader(
    private val context: Context,
    private val address: String,
    private val completion: (Reading?, String?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    @Volatile private var gatt: BluetoothGatt? = null
    /** The last step reached, so a timeout says where the read stalled. */
    @Volatile private var stage = "starting"
    private val deadline = Runnable { complete(null, "Timed out after 20 seconds while $stage") }
    private val closer = Runnable { closeGatt() }
    @Volatile private var rssi: Int? = null
    @Volatile private var holdsLock = false

    fun start() {
        // One Ring connection at a time across the whole app (manual reads and background checks).
        if (!inFlight.compareAndSet(false, true)) {
            complete(null, "Another Ring read is already running")
            return
        }
        holdsLock = true
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            complete(null, "Nearby devices permission required")
            return
        }
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) {
                complete(null, "Bluetooth is off")
                return
            }
            val device = adapter.getRemoteDevice(address)
            main.postDelayed(deadline, 20_000)
            step("connecting")
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            complete(null, "Connection unavailable: ${e.javaClass.simpleName}")
        }
    }

    fun cancel() = complete(null, "Cancelled")

    /** Logs each step with no address, name or reading, so logs stay safe to share. */
    private fun step(name: String) {
        stage = name
        Log.i(LOG_TAG, "Ring read: $name")
    }

    /** Main thread only: the result waiting for the connection to close. */
    private var pending: Pair<Reading?, String?>? = null

    private fun complete(reading: Reading?, error: String?) {
        if (!finished.compareAndSet(false, true)) return
        Log.i(LOG_TAG, if (reading != null) "Ring read: done" else "Ring read failed: $error")
        main.removeCallbacks(deadline)
        // Hang up now, but release the connection only once Android reports the link is down
        // (or after 2 s), so the Bluetooth stack isn't left holding a half-closed link.
        try { gatt?.disconnect() } catch (_: Exception) { }
        main.post {
            pending = reading to error
            if (gatt == null) closeGatt() else main.postDelayed(closer, 2_000)
        }
    }

    /**
     * Main thread only. The result is delivered, and the one-read lock released, only after
     * the connection is closed, so the next read (or the end of a background job) never
     * overlaps a link that is still open.
     */
    private fun closeGatt() {
        main.removeCallbacks(closer)
        gatt?.let { g ->
            gatt = null
            try { g.close() } catch (_: Exception) { }
        }
        val result = pending ?: return
        pending = null
        if (holdsLock) { holdsLock = false; inFlight.set(false) }
        completion(result.first, result.second)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) main.post { if (finished.get()) closeGatt() }
            if (finished.get()) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                complete(null, "BLE connection failed (status $status)")
                return
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                try {
                    step("discovering services")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                    if (!g.discoverServices()) complete(null, "BLE service discovery did not start")
                } catch (e: SecurityException) { complete(null, "Bluetooth permission denied") }
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                complete(null, "Ring disconnected before battery reply")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (finished.get()) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                complete(null, "BLE services failed (status $status)")
                return
            }
            val service = g.getService(DATA_SERVICE)
            val notify = service?.getCharacteristic(NOTIFY_UUID)
            val write = service?.getCharacteristic(WRITE_UUID)
            val cccd = notify?.getDescriptor(CCCD_UUID)
            if (notify == null || write == null || cccd == null) {
                complete(null, "Ring DATA service unavailable on this firmware")
                return
            }
            if ((notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 ||
                (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                complete(null, "Unexpected Ring GATT characteristic properties")
                return
            }
            try {
                step("subscribing to notifications")
                if (!g.setCharacteristicNotification(notify, true)) {
                    complete(null, "Could not enable Ring notifications")
                    return
                }
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                if (!g.writeDescriptor(cccd)) complete(null, "Could not subscribe to Ring notifications")
            } catch (e: SecurityException) { complete(null, "Bluetooth permission denied") }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (finished.get() || descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                complete(null, "Ring notification subscription failed ($status)")
                return
            }
            val characteristic = g.getService(DATA_SERVICE)?.getCharacteristic(WRITE_UUID)
            if (characteristic == null) {
                complete(null, "Ring command characteristic missing")
                return
            }
            try {
                step("waiting for the battery reply")
                // Channel 11, battery sync request only. No other device commands sent.
                val batteryRequest = byteArrayOf(0x0b, 0x0b, 0x03)
                if (Build.VERSION.SDK_INT >= 33) {
                    val result = g.writeCharacteristic(characteristic, batteryRequest,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    if (result != android.bluetooth.BluetoothStatusCodes.SUCCESS)
                        complete(null, "Battery request rejected ($result)")
                    else g.readRemoteRssi()   // optional; kept only if it arrives before the reply
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    characteristic.value = batteryRequest
                    @Suppress("DEPRECATION")
                    if (!g.writeCharacteristic(characteristic)) complete(null, "Battery request failed")
                    else g.readRemoteRssi()
                }
            } catch (e: SecurityException) { complete(null, "Bluetooth permission denied") }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) rssi = value
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            accept(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            accept(characteristic.uuid, value)
        }

        private fun accept(uuid: UUID, value: ByteArray) {
            if (finished.get() || uuid != NOTIFY_UUID) return
            val report = RingBatteryProtocol.parseDetailedReport(value) ?: return
            complete(Reading(BatteryStore.RING_ID, System.currentTimeMillis(), report.percent,
                report.charging, "ring_ble", detail = ReadingDetail.clock(context, null)
                    .copy(rssiDbm = rssi, rawExtra = report.rawExtra)), null)
        }
    }

    companion object {
        private const val LOG_TAG = "BatteryLabRing"
        private val inFlight = AtomicBoolean(false)
        private val DATA_SERVICE = UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID = UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b")
        private val WRITE_UUID = UUID.fromString("63e30bad-4206-4596-839f-e47cbf7a4b5d")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * RF Lab's ring_link holds this same one-connection lock from before connect until after
         * close (R7), so a battery read never overlaps it. True when the lock was free and is now held.
         */
        fun tryTakeRingGate(): Boolean = inFlight.compareAndSet(false, true)

        /** Releases the lock taken by [tryTakeRingGate]. Called exactly once per successful take. */
        fun releaseRingGate() = inFlight.set(false)

        /** Name-only hint for sorting the picker; never used to decide a device's identity. */
        fun looksLikeRing(name: String?): Boolean = name?.contains("ring", ignoreCase = true) == true

        fun pairedDevices(context: Context): List<BluetoothDevice> {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
            return try {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                    .adapter?.bondedDevices?.sortedBy { it.name ?: "" } ?: emptyList()
            } catch (_: SecurityException) { emptyList() }
        }
    }
}
