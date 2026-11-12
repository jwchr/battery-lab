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
    private var gatt: BluetoothGatt? = null
    private val deadline = Runnable { complete(null, "Timed out after 20 seconds") }

    fun start() {
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
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            complete(null, "Connection unavailable: ${e.javaClass.simpleName}")
        }
    }

    fun cancel() = complete(null, "Cancelled")

    private fun complete(reading: Reading?, error: String?) {
        if (!finished.compareAndSet(false, true)) return
        main.removeCallbacks(deadline)
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        main.post { completion(reading, error) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (finished.get()) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                complete(null, "BLE connection failed (status $status)")
                return
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                try {
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
                // Channel 11, battery sync request only. No other device commands sent.
                val batteryRequest = byteArrayOf(0x0b, 0x0b, 0x03)
                if (Build.VERSION.SDK_INT >= 33) {
                    val result = g.writeCharacteristic(characteristic, batteryRequest,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    if (result != android.bluetooth.BluetoothStatusCodes.SUCCESS)
                        complete(null, "Battery request rejected ($result)")
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    characteristic.value = batteryRequest
                    @Suppress("DEPRECATION")
                    if (!g.writeCharacteristic(characteristic)) complete(null, "Battery request failed")
                }
            } catch (e: SecurityException) { complete(null, "Bluetooth permission denied") }
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
            val result = RingBatteryProtocol.parseBatteryReport(value) ?: return
            complete(Reading("ring:$address", System.currentTimeMillis(), result.first,
                result.second, "ring_ble"), null)
        }
    }

    companion object {
        private val DATA_SERVICE = UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID = UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b")
        private val WRITE_UUID = UUID.fromString("63e30bad-4206-4596-839f-e47cbf7a4b5d")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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
