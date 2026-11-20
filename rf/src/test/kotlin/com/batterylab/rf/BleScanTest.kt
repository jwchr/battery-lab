package com.batterylab.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A scan port that hands results over only when the test says so. Synthetic addresses only. */
class FakeScanPort : BleScanPort {
    var blocked: String? = null
    var bt = true
    var location = true
    var starts = 0
    var stops = 0
    var onHit: ((ScanHit) -> Unit)? = null
    var onFailed: ((Int) -> Unit)? = null
    var btListener: ((Boolean) -> Unit)? = null
    override fun blocker() = blocked
    override fun bluetoothOn() = bt
    override fun locationOn() = location
    override fun startScan(onHit: (ScanHit) -> Unit, onFailed: (Int) -> Unit): Boolean {
        starts++; this.onHit = onHit; this.onFailed = onFailed; return true
    }
    override fun stopScan() { stops++ }
    override fun watchBluetooth(listener: (Boolean) -> Unit) { btListener = listener }
    override fun unwatchBluetooth() { btListener = null }
}

/** Synthetic, locally administered addresses: never a real device. */
fun fakeAddress(i: Int) = "02:00:00:00:%02X:%02X".format((i shr 8) and 0xff, i and 0xff)
const val TARGET = "02:00:00:00:FF:FF"

/** T15 (the scan stream), T8 (privacy) and T13 (lifecycle). */
class BleScanTest {
    private val macPattern = Regex("""(?i)\b[0-9a-f]{2}([:-])[0-9a-f]{2}(\1[0-9a-f]{2}){4}\b""")

    private fun hub(port: FakeScanPort, sch: FakeScheduler, capacity: Int = BleScanHub.QUEUE_SIZE, key: ByteArray = ByteArray(32) { 7 }) =
        BleScanHub(port, sch, { it.equals(TARGET, ignoreCase = true) }, capacity = capacity, key = key)

    private fun hit(port: FakeScanPort, sch: FakeScheduler, address: String, rssi: Int) =
        port.onHit!!(ScanHit(address, rssi, sch.now, sch.wall, true))

    @Test fun oneScanStartPerSessionPlusOneEachTimeBluetoothReturns() {
        val port = FakeScanPort(); val sch = FakeScheduler()
        val h = hub(port, sch)
        h.scanStream.start(RecordingSink()); h.othersStream.start(RecordingSink())
        assertEquals(1, port.starts)
        sch.advanceSeconds(30.0)
        assertEquals("no restarts while Bluetooth stays on", 1, port.starts)
        port.btListener!!(false)
        assertEquals(1, port.stops)
        port.btListener!!(true)
        sch.advanceSeconds(9.9)
        assertEquals("not sooner than 10 seconds", 1, port.starts)
        sch.advanceSeconds(0.2)
        assertEquals(2, port.starts)
        port.btListener!!(false); port.btListener!!(true); port.btListener!!(true)
        sch.advanceSeconds(20.0)
        assertEquals(3, port.starts)
        h.scanStream.stop(); h.othersStream.stop()
        assertEquals(3, port.stops)
        assertEquals(null, port.btListener)
    }

    @Test fun stopDuringTheRestartDelayCancelsTheRestart() {
        val port = FakeScanPort(); val sch = FakeScheduler()
        val h = hub(port, sch)
        h.scanStream.start(RecordingSink())
        port.btListener!!(false); port.btListener!!(true)
        h.scanStream.stop()
        sch.advanceSeconds(30.0)
        assertEquals(1, port.starts)
    }

    @Test fun aFullQueueRaisesDroppedAndDoesNotBlock() {
        val port = FakeScanPort(); val sch = FakeScheduler()
        val h = hub(port, sch, capacity = 100)
        val scan = RecordingSink()
        h.scanStream.start(scan); h.othersStream.start(RecordingSink())
        val t0 = System.nanoTime()
        for (i in 0 until 1_000) hit(port, sch, fakeAddress(i), -70)
        assertTrue("offering never blocks", (System.nanoTime() - t0) / 1_000_000 < 1_000)
        sch.advanceSeconds(0.3)
        assertEquals(900, scan.dropped)
        h.scanStream.stop()
    }

    @Test fun onlyTheTargetGetsRowsAndOthersAreOneRowPerSecondWithNoIdentifier() {
        val port = FakeScanPort(); val sch = FakeScheduler()
        val h = hub(port, sch)
        val scan = RecordingSink(); val others = RecordingSink()
        h.scanStream.start(scan); h.othersStream.start(others)
        for (sec in 0 until 5) {
            hit(port, sch, TARGET, -50 - sec)
            for (i in 0 until 3 + sec) hit(port, sch, fakeAddress(i), -80 + i)
            sch.advanceSeconds(1.0)
        }
        sch.advanceSeconds(2.0)
        assertEquals(5, scan.rows.size)
        assertTrue(scan.rows.all { it.extra[BleScanHub.ALIAS] == "ring" && it.status == Status.OK })
        // Seconds 0 to 4 have results; second 5 is empty and still gets a row.
        assertEquals(listOf("3", "4", "5", "6", "7", "0"), others.rows.take(6).map { it.extra[BleScanHub.DEVICES] })
        assertEquals(-78, others.rows[0].rssiDbm)
        assertEquals(null, others.rows[5].rssiDbm)
        assertEquals(Status.NO_VALUE, others.rows[5].status)
        assertEquals("distinct across the session", 7, others.othersSeen)
        for (r in scan.rows + others.rows) {
            for (v in r.extra.values) assertFalse(macPattern.containsMatchIn(v))
        }
        h.scanStream.stop()
    }

    @Test fun blockedScansDoNotStartAndLocationOffStillScans() {
        for (reason in listOf(Status.PERMISSION_NEEDED, Status.PRECISE_NEEDED, Status.UNSUPPORTED)) {
            val port = FakeScanPort().apply { blocked = reason }; val sch = FakeScheduler()
            val h = hub(port, sch)
            val scan = RecordingSink(); val others = RecordingSink()
            h.scanStream.start(scan); h.othersStream.start(others)
            assertEquals(0, port.starts)
            assertEquals(reason, scan.last()); assertEquals(reason, others.last())
            h.scanStream.stop()
        }
        val port = FakeScanPort().apply { location = false }; val sch = FakeScheduler()
        val h = hub(port, sch)
        val scan = RecordingSink()
        h.scanStream.start(scan)
        assertEquals(1, port.starts)
        assertEquals(Status.LOCATION_OFF, scan.last())
        h.scanStream.stop()
    }

    @Test fun aScanFailureIsStoredWithItsCode() {
        val port = FakeScanPort(); val sch = FakeScheduler()
        val h = hub(port, sch)
        val scan = RecordingSink()
        h.scanStream.start(scan)
        port.onFailed!!(2)
        assertEquals(Status.ERROR to "scan failed (code 2)", scan.statuses.last())
        h.scanStream.stop()
    }

    @Test fun aMyDevicesSessionWritesNoIdentifierKeyOrName() {
        val key = ByteArray(32) { (it * 13 + 5).toByte() }
        val keyHex = key.joinToString("") { "%02x".format(it) }
        val (c, sch) = controller()
        val scanPort = FakeScanPort()
        val ringPort = FakeRingPort()
        val r = c.start(StartRequest(Scope.MY_DEVICES, Provenance.LIVE, 20, streams = { s ->
            val h = BleScanHub(scanPort, s, { it == TARGET }, key = key)
            listOf(SimulatedStream.pair(3, s).first(), RingLinkStream(ringPort, s), h.scanStream, h.othersStream)
        })) as StartResult.Started
        ringPort.up()
        for (sec in 0 until 10) {
            hit(scanPort, sch, TARGET, -55)
            for (i in 0 until 20) hit(scanPort, sch, fakeAddress(i), -75)
            sch.advanceSeconds(1.0)
            if (ringPort.outstandingReads > 0) ringPort.answer(-60)
        }
        c.stop()
        val dir = c.store.dir(r.id)
        val files = dir.listFiles()!!.filter { it.isFile }
        assertTrue(files.any { it.name == "obs_ble_others.csv" })
        for (f in files) {
            val text = f.readText()
            assertFalse("${f.name} holds an address", macPattern.containsMatchIn(text))
            assertFalse("${f.name} holds the key", text.lowercase().contains(keyHex))
            assertFalse(text.contains("02:00:00"))
        }
        val scanRows = SessionReader.obs(dir, "ble_scan")
        assertEquals("only the target's rows", 10, scanRows.rows.size)
        val streams = File(dir, FileNames.STREAMS).readText()
        assertTrue("others_seen is filled in", streams.lines().any { it.startsWith("ble_others,") && it.endsWith(",20") })
        assertEquals(1, ringPort.gateReleased)
    }

    @Test fun aStopBetweenDrainsKeepsQueuedResultsAndTheSecondInProgress() {
        val (c, sch) = controller()
        val scanPort = FakeScanPort()
        val r = c.start(StartRequest(Scope.MY_DEVICES, Provenance.LIVE, 20, streams = { s ->
            val h = BleScanHub(scanPort, s, { it == TARGET })
            listOf(h.scanStream, h.othersStream)
        })) as StartResult.Started
        sch.advanceSeconds(2.1)
        hit(scanPort, sch, TARGET, -52)
        for (i in 0 until 4) hit(scanPort, sch, fakeAddress(i), -70)
        c.stop()                            // before the next 250 ms drain
        val dir = c.store.dir(r.id)
        assertEquals(listOf("-52"), SessionReader.obs(dir, "ble_scan").let { t -> t.rows.map { t.cell(it, "rssi_dbm") } })
        val others = SessionReader.obs(dir, "ble_others")
        assertEquals("4", others.cell(others.rows.last(), BleScanHub.DEVICES))
        val stopNs = sch.now
        assertTrue(others.rows.all { others.cell(it, "t_boot_ns")!!.toLong() <= stopNs })
    }

    @Test fun rotationKeepsTheSessionAndLeavingEndsItWithin3Seconds() {
        val (c, sch) = controller()
        c.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, 60, streams = { SimulatedStream.pair(1, it) }))
        c.screenStopped(changingConfigurations = true)
        Thread.sleep(200)
        assertTrue(c.running)
        val t0 = System.nanoTime()
        c.screenStopped(changingConfigurations = false)
        while (c.running && System.nanoTime() - t0 < 3_000_000_000L) Thread.sleep(10)
        assertFalse(c.running)
        assertEquals(StopReason.LEFT_SCREEN, c.snapshot()!!.stopReason)
    }

    @Test fun bluetoothOffSetsTheThreeBluetoothStreamsOffAndOthersKeepRecording() {
        val (c, sch) = controller()
        val scanPort = FakeScanPort(); val ringPort = FakeRingPort()
        val r = c.start(StartRequest(Scope.MY_DEVICES, Provenance.LIVE, 60, streams = { s ->
            val h = BleScanHub(scanPort, s, { it == TARGET })
            listOf(SimulatedStream.pair(3, s).first(), RingLinkStream(ringPort, s), h.scanStream, h.othersStream)
        })) as StartResult.Started
        ringPort.up()
        sch.advanceSeconds(3.0)
        ringPort.btListener!!(false); scanPort.btListener!!(false)
        val snap = c.snapshot()!!
        val byId = snap.streams.associateBy { it.id }
        assertEquals(Status.OFF, byId["ring_link"]!!.status)
        assertEquals(Status.OFF, byId["ble_scan"]!!.status)
        assertEquals(Status.OFF, byId["ble_others"]!!.status)
        val simId = snap.streams.first { it.kind == Kinds.SIMULATED }.id
        val before = byId[simId]!!.rows
        sch.advanceSeconds(5.0)
        assertTrue("the other stream keeps recording", c.snapshot()!!.streams.first { it.id == simId }.rows > before)
        c.stop()
        assertTrue(c.store.dir(r.id).exists())
    }
}
