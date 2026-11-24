package com.batterylab.personal.rf

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.batterylab.personal.BatteryStore
import com.batterylab.personal.RingReader
import com.batterylab.rf.RingLinkEvents
import com.batterylab.rf.RingLinkPort

/**
 * The Android side of ring_link (R6): the only place RF Lab touches BluetoothGatt. It connects
 * to the Ring already chosen in Battery Lab, and calls only connectGatt, disconnect, close and
 * readRemoteRssi. Every callback arrives on this stream's own HandlerThread. The address stays
 * in memory; nothing here writes it anywhere.
 */
class RingLinkAdapter(context: Context, private val address: String) : RingLinkPort {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val watch = BluetoothWatch(app)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var gatt: BluetoothGatt? = null

    override fun open() {
        if (thread != null) return
        val t = HandlerThread("rf-ring").apply { start() }
        thread = t
        handler = Handler(t.looper)
    }

    override fun bluetoothOn() = manager?.adapter?.isEnabled == true

    override fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** A database that cannot be read counts as a running test, so RF Lab stays out of the way. */
    override fun testRunning() = try { BatteryStore.get(app).activeExperiment() != null } catch (_: Exception) { true }

    override fun tryTakeGate() = RingReader.tryTakeRingGate()
    override fun releaseGate() = RingReader.releaseRingGate()

    override fun connect(link: Int, events: RingLinkEvents): Boolean {
        val h = handler ?: return false
        val device = manager?.adapter?.getRemoteDevice(address) ?: return false
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) =
                events.connectionChanged(link, newState == BluetoothProfile.STATE_CONNECTED, status)

            override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = events.rssi(link, rssi, status)
        }
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h)
        return gatt != null
    }

    override fun disconnect() { gatt?.disconnect() }

    override fun close() {
        val g = gatt ?: return
        gatt = null
        g.close()
    }

    override fun readRssi() = gatt?.readRemoteRssi() == true

    override fun watchBluetooth(listener: (Boolean) -> Unit) = watch.start(handler, listener)
    override fun unwatchBluetooth() = watch.stop()

    override fun shutdown() {
        val t = thread ?: return
        thread = null
        handler = null
        t.quitSafely()
        try { t.join(500) } catch (_: InterruptedException) { }
    }
}
