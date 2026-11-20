package com.batterylab.personal.rf

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.batterylab.rf.Kinds
import com.batterylab.rf.Obs
import com.batterylab.rf.Status
import com.batterylab.rf.Stream
import com.batterylab.rf.StreamSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's current Wi-Fi link, once per second (R5). A network callback for the Wi-Fi transport
 * says which network that is; each second reads its capabilities and takes the RSSI from the
 * WifiInfo in transportInfo when valid, otherwise signalStrength, and records which one it used.
 * It reads no network name, and needs only ACCESS_NETWORK_STATE, which the app already has.
 */
class WifiLinkStream(context: Context) : Stream {
    override val id = "wifi_link"
    override val kind = Kinds.WIFI_LINK
    override val nominalRateHz = 1.0
    override val extraColumns = listOf(SOURCE)

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var network: Network? = null
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    companion object {
        const val SOURCE = "rssi_source"
        const val FROM_WIFI_INFO = "wifi_info"
        const val FROM_SIGNAL_STRENGTH = "signal_strength"
        /** WifiInfo reports -127 when it has no value; real readings sit well inside this range. */
        private fun valid(dbm: Int) = dbm in -126..-1
    }

    override fun start(sink: StreamSink) {
        if (!started.compareAndSet(false, true) || stopped.get()) return
        if (Build.VERSION.SDK_INT < 29) {
            sink.status(Status.UNSUPPORTED, "Needs Android 10 or later")
            return
        }
        val t = HandlerThread("rf-wifi").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { network = n }
            override fun onLost(n: Network) { if (network == n) network = null }
        }
        callback = cb
        cm.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb, h)
        val origin = SystemClock.uptimeMillis()
        // Tick n runs at origin + n seconds: the first at 1 s, then one each second.
        var n = 1L
        val tick = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                sample(sink)
                n++
                h.postAtTime(this, origin + 1000 * n)
            }
        }
        h.postAtTime(tick, origin + 1000 * n)
    }

    private fun sample(sink: StreamSink) {
        val now = SystemClock.elapsedRealtimeNanos()
        val wall = System.currentTimeMillis()
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        if (caps == null || Build.VERSION.SDK_INT < 29) {
            sink.status(Status.NOT_CONNECTED)
            sink.observe(Obs(now, wall, 0, null, Status.NOT_CONNECTED, mapOf(SOURCE to "")))
            return
        }
        val fromInfo = (caps.transportInfo as? WifiInfo)?.rssi?.takeIf { valid(it) }
        val fromCaps = caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && valid(it) }
        val value = fromInfo ?: fromCaps
        val source = when { fromInfo != null -> FROM_WIFI_INFO; fromCaps != null -> FROM_SIGNAL_STRENGTH; else -> "" }
        if (value == null) {
            sink.status(Status.NO_VALUE)
            sink.observe(Obs(now, wall, 0, null, Status.NO_VALUE, mapOf(SOURCE to "")))
        } else {
            sink.status(Status.RECORDING)
            sink.observe(Obs(now, wall, 0, value, Status.OK, mapOf(SOURCE to source)))
        }
    }

    /** Safe to call twice; returns once the callback is unregistered and the thread has ended (3 s at most). */
    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        handler?.removeCallbacksAndMessages(null)
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        callback = null
        thread?.let { it.quitSafely(); try { it.join(3_000) } catch (_: InterruptedException) { } }
        thread = null
        handler = null
        network = null
    }
}
