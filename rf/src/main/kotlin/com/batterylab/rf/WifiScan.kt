package com.batterylab.rf

/** One access point from a scan batch. [bssid] and [ssid] stay in memory; files get a device number. */
class WifiHit(val bssid: String, val ssid: String?, val levelDbm: Int, val freqMhz: Int, val tBootNs: Long)

/** What the Wi-Fi scan needs from Android (R26). */
interface WifiScanPort {
    /** Null when scanning may run; otherwise the status that says why not. */
    fun blocker(): String?
    fun locationOn(): Boolean
    /** Asks Android for a scan. False means Android refused the request (throttled, idle, or failed). */
    fun requestScan(): Boolean
    /** Every scan-results broadcast, ours or another app's, delivered only between these two calls. */
    fun watchResults(listener: (updated: Boolean) -> Unit)
    fun unwatchResults()
    /** The current batch. */
    fun results(): List<WifiHit>
}

/**
 * wifi_scan (R26): asks for a scan at start and then at most once every 31 seconds, which stays
 * inside Android's limit of four requests per two minutes, and never while Location is off. Every
 * results broadcast is handled, including those from other apps' scans. A batch Android marks as
 * not updated is stored as an event with no rows. From an updated batch each access point is stored
 * with its own timestamp, level and frequency, skipping one already stored with the same timestamp.
 */
class WifiScanStream(
    private val port: WifiScanPort,
    private val scheduler: Scheduler,
    private val emitters: EmitterTable,
) : Stream {
    companion object {
        const val REQUEST_GAP_NS = 31 * NS_PER_S
        const val FREQ = "freq_mhz"
        const val NOT_UPDATED = "results_not_updated"
        const val REFUSED = "scan_request_refused"
        const val REQUESTED = "scan_requested"
    }

    override val id = "wifi_scan"
    override val kind = Kinds.WIFI_SCAN
    override val nominalRateHz = 0.0
    override val extraColumns = listOf(FREQ)

    private val lock = Any()
    private var sink: StreamSink? = null
    private var started = false
    private var stopped = false
    private var watching = false
    private var timer: Cancellable? = null
    private val stored = HashSet<String>()
    /** Requests this session, for T19. */
    var requests = 0
        private set

    override fun start(sink: StreamSink) { synchronized(lock) {
        if (started || stopped) return
        started = true
        this.sink = sink
        val blocked = try { port.blocker() } catch (_: Exception) { Status.PERMISSION_NEEDED }
        if (blocked != null) { sink.status(blocked); return }
        try { port.watchResults { updated -> batch(updated) }; watching = true }
        catch (_: SecurityException) { sink.status(Status.PERMISSION_NEEDED); return }
        tick()
    } }

    /** Lock held. One request now if allowed, then the next one 31 seconds later. */
    private fun tick() {
        if (stopped) return
        val s = sink ?: return
        val locOn = try { port.locationOn() } catch (_: Exception) { false }
        if (!locOn) s.status(Status.LOCATION_OFF)
        else {
            val ok = try { port.requestScan() } catch (_: SecurityException) { s.status(Status.PERMISSION_NEEDED); false }
                catch (_: Exception) { false }
            requests++
            if (ok) { s.status(Status.RECORDING); s.note(REQUESTED) } else s.note(REFUSED, "throttled, idle or failed")
        }
        timer = scheduler.schedule(REQUEST_GAP_NS) { synchronized(lock) { tick() } }
    }

    private fun batch(updated: Boolean) { synchronized(lock) {
        if (stopped || !started) return
        val s = sink ?: return
        if (!updated) { s.note(NOT_UPDATED); return }
        val hits = try { port.results() } catch (_: SecurityException) { s.status(Status.PERMISSION_NEEDED); return }
            catch (_: Exception) { return }
        val nowNs = scheduler.bootNs()
        val nowWall = scheduler.wallMs()
        var lost = 0
        for (h in hits) {
            if (!stored.add("${h.bssid.uppercase()}|${h.tBootNs}")) continue
            val wall = nowWall - (nowNs - h.tBootNs) / 1_000_000
            val n = emitters.see(EmitterTable.WIFI, h.bssid, h.levelDbm, h.tBootNs, wall,
                band = EmitterTable.band(h.freqMhz), name = h.ssid)
            if (n == null) { lost++; continue }
            s.observe(Obs(h.tBootNs, wall, n, h.levelDbm, Status.OK, mapOf(FREQ to h.freqMhz.toString())))
        }
        if (lost > 0) s.dropped(lost)
    } }

    /** Safe to call twice. After it returns no request is made and no late broadcast is written. */
    override fun stop() { synchronized(lock) {
        if (stopped) return
        stopped = true
        timer?.cancel(); timer = null
        if (watching) { try { port.unwatchResults() } catch (_: Exception) { } }
        watching = false
        stored.clear()
    } }
}
