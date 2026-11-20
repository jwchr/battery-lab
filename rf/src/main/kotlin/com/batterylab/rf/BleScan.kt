package com.batterylab.rf

import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One scan result as the adapter hands it over. [address] stays in memory and is never written. */
class ScanHit(val address: String, val rssi: Int, val tBootNs: Long, val tWallMs: Long, val connectable: Boolean?)

/** What the Bluetooth scan needs from Android (R24). */
interface BleScanPort {
    /** Null when the scan may start; otherwise the status that says why not. */
    fun blocker(): String?
    fun bluetoothOn(): Boolean
    fun locationOn(): Boolean
    /**
     * Starts one unfiltered scan: low-latency mode, all matches, no report delay, legacy mode off.
     * Results go to [onHit] on the adapter's callback thread; a failure goes to [onFailed].
     */
    fun startScan(onHit: (ScanHit) -> Unit, onFailed: (Int) -> Unit): Boolean
    fun stopScan()
    /** Bluetooth on/off changes, delivered only between these two calls (a session-only receiver). */
    fun watchBluetooth(listener: (Boolean) -> Unit)
    fun unwatchBluetooth()
}

/**
 * One Bluetooth scan per session feeding two streams (R24, R25):
 * - ble_scan: a row for each result from the pinned target (the Ring, alias ring), nothing else.
 * - ble_others: one row per second with the strongest non-target signal and how many distinct
 *   non-target devices were heard that second. No identifier is written.
 *
 * Results go into a queue of 8,192 and are written off the callback thread. Overflow is counted in
 * dropped and never blocks. Distinct devices are counted by a keyed hash whose 32-byte key exists
 * only in memory for this session; the key and the sets are discarded at stop.
 */
class BleScanHub(
    private val port: BleScanPort,
    private val scheduler: Scheduler,
    private val isTarget: (String) -> Boolean,
    private val targetAlias: String = "ring",
    capacity: Int = QUEUE_SIZE,
    key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) {
    companion object {
        const val QUEUE_SIZE = 8_192
        const val ALIAS = "alias"
        const val CONNECTABLE = "connectable"
        const val DEVICES = "devices"
        const val DRAIN_NS = 250_000_000L
        /** A second is written once results for it can no longer be waiting in the queue. */
        const val GRACE_NS = 500_000_000L
        const val RESTART_NS = 10 * NS_PER_S
    }

    private val lock = Any()
    private val queue = ArrayBlockingQueue<ScanHit>(capacity)
    private val overflow = AtomicInteger()
    private var mac: Mac? = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    private var scanSink: StreamSink? = null
    private var othersSink: StreamSink? = null
    private var started = false
    private var stopped = false
    private var scanning = false
    private var btOn = false
    private var status = Status.STARTING
    private var detail = ""
    private var drainTask: Cancellable? = null
    private var restartTask: Cancellable? = null
    private var originNs = 0L
    private var originWallMs = 0L
    /** The second being filled, counted from [originNs]; -1 while not scanning. */
    private var bucket = -1L
    private var strongest: Int? = null
    private val bucketSet = HashSet<Long>()
    private val allSet = HashSet<Long>()
    /** Scan starts this session, for T15. */
    var scanStarts = 0
        private set

    val scanStream: Stream = object : Stream {
        override val id = "ble_scan"
        override val kind = Kinds.BLE_SCAN
        override val nominalRateHz = 0.0
        override val extraColumns = listOf(ALIAS, CONNECTABLE)
        override fun start(sink: StreamSink) = startScanStream(sink)
        override fun stop() = stopAll()
    }

    val othersStream: Stream = object : Stream {
        override val id = "ble_others"
        override val kind = Kinds.BLE_OTHERS
        override val nominalRateHz = 1.0
        override val extraColumns = listOf(DEVICES)
        override fun start(sink: StreamSink) = startOthersStream(sink)
        override fun stop() = stopAll()
    }

    private fun startScanStream(sink: StreamSink) { synchronized(lock) {
        if (started || stopped) return
        started = true
        scanSink = sink
        val blocked = try { port.blocker() } catch (_: Exception) { Status.PERMISSION_NEEDED }
        if (blocked != null) { setStatus(blocked); return }
        try {
            port.watchBluetooth { on -> bluetoothChanged(on) }
            btOn = port.bluetoothOn()
        } catch (_: SecurityException) { setStatus(Status.PERMISSION_NEEDED); return }
        originNs = scheduler.bootNs()
        originWallMs = scheduler.wallMs()
        drainTask = scheduler.schedule(DRAIN_NS) { drainLoop() }
        if (btOn) begin() else setStatus(Status.OFF)
    } }

    private fun startOthersStream(sink: StreamSink) { synchronized(lock) {
        if (stopped) return
        othersSink = sink
        sink.status(status, detail)
    } }

    /** Lock held. Starts the scan. With Location off the scan still starts and the status says so. */
    private fun begin() {
        if (stopped || scanning) return
        val ok = try {
            port.startScan({ hit -> if (!queue.offer(hit)) overflow.incrementAndGet() }, { code -> scanFailed(code) })
        } catch (_: SecurityException) { setStatus(Status.PERMISSION_NEEDED); return }
        catch (e: Exception) { false }
        scanStarts++
        if (!ok) { setStatus(Status.ERROR, "scan did not start"); return }
        scanning = true
        bucket = (scheduler.bootNs() - originNs) / NS_PER_S
        strongest = null
        bucketSet.clear()
        val locOn = try { port.locationOn() } catch (_: Exception) { true }
        setStatus(if (locOn) Status.RECORDING else Status.LOCATION_OFF)
    }

    private fun scanFailed(code: Int) { synchronized(lock) {
        if (stopped) return
        scanning = false
        setStatus(Status.ERROR, "scan failed (code $code)")
    } }

    /** Lock held. Both streams share one status. */
    private fun setStatus(s: String, d: String = "") {
        status = s
        detail = d
        scanSink?.status(s, d)
        othersSink?.status(s, d)
    }

    /** Bluetooth off stops the scan; Bluetooth on starts it again once, 10 seconds later. */
    fun bluetoothChanged(on: Boolean) { synchronized(lock) {
        if (stopped || !started || on == btOn) return
        btOn = on
        restartTask?.cancel(); restartTask = null
        if (!on) {
            if (scanning) { try { port.stopScan() } catch (_: Exception) { } }
            scanning = false
            drainNow(scheduler.bootNs(), final = true)
            bucket = -1
            setStatus(Status.OFF)
        } else {
            restartTask = scheduler.schedule(RESTART_NS) { synchronized(lock) { if (btOn && !stopped) begin() } }
        }
    } }

    private fun drainLoop() {
        synchronized(lock) {
            if (stopped) return
            drainNow(scheduler.bootNs())
            drainTask = scheduler.schedule(DRAIN_NS) { drainLoop() }
        }
    }

    /**
     * Lock held. Writes queued results, then a ble_others row for every second that ended at least
     * [GRACE_NS] before [nowNs], empty seconds included. [final] also writes the second in progress.
     */
    private fun drainNow(nowNs: Long, final: Boolean = false) {
        val lost = overflow.getAndSet(0)
        if (lost > 0) scanSink?.dropped(lost)
        while (true) accept(queue.poll() ?: break)
        if (bucket < 0) return
        if (final) { flushBucket(); return }
        while (originNs + (bucket + 1) * NS_PER_S <= nowNs - GRACE_NS) { flushBucket(); bucket++ }
    }

    /** Lock held. */
    private fun accept(hit: ScanHit) {
        if (isTarget(hit.address)) {
            scanSink?.observe(Obs(hit.tBootNs, hit.tWallMs, 0, hit.rssi, Status.OK,
                mapOf(ALIAS to targetAlias, CONNECTABLE to (hit.connectable?.toString() ?: ""))))
            return
        }
        if (bucket < 0) return
        val h = hash(hit.address) ?: return
        // A result for a second already written counts toward the current one.
        val b = minOf((hit.tBootNs - originNs) / NS_PER_S, (scheduler.bootNs() - originNs) / NS_PER_S)
        while (bucket < b) { flushBucket(); bucket++ }
        bucketSet.add(h)
        if (allSet.add(h)) othersSink?.othersSeen(allSet.size)
        val s = strongest
        if (s == null || hit.rssi > s) strongest = hit.rssi
    }

    /** Lock held. One ble_others row for the current second, then a fresh, empty second. */
    private fun flushBucket() {
        val end = originNs + (bucket + 1) * NS_PER_S
        val s = strongest
        othersSink?.observe(Obs(end, originWallMs + (bucket + 1) * 1000, 0, s, if (s != null) Status.OK else Status.NO_VALUE,
            mapOf(DEVICES to bucketSet.size.toString())))
        strongest = null
        bucketSet.clear()
    }

    private fun hash(address: String): Long? {
        val m = mac ?: return null
        val d = m.doFinal(("ble|" + address.uppercase()).toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xff)
        return v
    }

    /** Either stream's stop ends the scan. Safe to call twice. */
    private fun stopAll() { synchronized(lock) {
        if (stopped) return
        stopped = true
        drainTask?.cancel(); drainTask = null
        restartTask?.cancel(); restartTask = null
        if (scanning) { try { port.stopScan() } catch (_: Exception) { } }
        scanning = false
        if (started) { try { port.unwatchBluetooth() } catch (_: Exception) { } }
        queue.clear()
        bucketSet.clear()
        allSet.clear()
        mac = null
    } }
}
