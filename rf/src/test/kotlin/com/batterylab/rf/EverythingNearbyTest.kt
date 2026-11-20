package com.batterylab.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** A Wi-Fi scan port driven by the test. Synthetic, locally administered addresses only. */
class FakeWifiPort(private val sch: FakeScheduler) : WifiScanPort {
    var blocked: String? = null
    var location = true
    var accept = true
    var throwOnRequest = false
    var throwOnResults = false
    val requestTimes = mutableListOf<Long>()
    var listener: ((Boolean) -> Unit)? = null
    var unwatches = 0
    var batch: List<WifiHit> = emptyList()
    override fun blocker() = blocked
    override fun locationOn() = location
    override fun requestScan(): Boolean {
        if (throwOnRequest) throw IllegalStateException("radio")
        requestTimes += sch.now; return accept
    }
    override fun watchResults(listener: (Boolean) -> Unit) { this.listener = listener }
    override fun unwatchResults() { listener = null; unwatches++ }
    override fun results(): List<WifiHit> { if (throwOnResults) throw IllegalStateException("radio"); return batch }
}

/** Milestone 2: T17 (keys, names, export), T19 (Wi-Fi scan), T8 for "Everything nearby", sizes. */
class EverythingNearbyTest {
    private val macPattern = Regex("""(?i)\b[0-9a-f]{2}([:-])[0-9a-f]{2}(\1[0-9a-f]{2}){4}\b""")

    private fun ap(i: Int, sch: FakeScheduler, level: Int = -60, freq: Int = 2_437, ts: Long = sch.now - 1_000_000) =
        WifiHit(fakeAddress(0x500 + i), "Net$i", level, freq, ts)

    private fun wifi(configure: FakeWifiPort.() -> Unit = {}): Triple<WifiScanStream, FakeWifiPort, Pair<FakeScheduler, RecordingSink>> {
        val sch = FakeScheduler()
        val port = FakeWifiPort(sch).apply(configure)
        val table = EmitterTable(InstallKey(File(newRoot(), "install.key")), named = true)
        val s = WifiScanStream(port, sch, table)
        val sink = RecordingSink()
        s.start(sink)
        return Triple(s, port, sch to sink)
    }

    // T19

    @Test fun requestsAreAtLeast31SecondsApart() {
        val (s, port, p) = wifi()
        val (sch, _) = p
        assertEquals(1, port.requestTimes.size)
        sch.advanceSeconds(30.9)
        assertEquals(1, port.requestTimes.size)
        sch.advanceSeconds(120.0)
        assertTrue(port.requestTimes.size in 4..5)
        port.requestTimes.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 31 * 1_000_000_000L) }
        // Never more than four requests in any two minutes.
        for (t in port.requestTimes) assertTrue(port.requestTimes.count { it >= t && it < t + 120_000_000_000L } <= 4)
        s.stop()
    }

    @Test fun aRefusedRequestIsStoredAsAnEventAndAnExceptionToo() {
        val (s, port, p) = wifi { accept = false }
        val (sch, sink) = p
        assertEquals(WifiScanStream.REFUSED, sink.notes.last().first)
        port.accept = true; port.throwOnRequest = true
        sch.advanceSeconds(31.0)
        assertEquals(2, sink.notes.count { it.first == WifiScanStream.REFUSED })
        s.stop()
    }

    @Test fun aBatchMarkedNotUpdatedStoresAnEventAndNoRows() {
        val (s, port, p) = wifi()
        val (sch, sink) = p
        port.batch = listOf(ap(1, sch), ap(2, sch))
        port.listener!!(false)
        assertEquals(0, sink.rows.size)
        assertTrue(sink.notes.any { it.first == WifiScanStream.NOT_UPDATED })
        s.stop()
    }

    @Test fun aResultAlreadyStoredIsSkippedAndOtherAppsBatchesAreStored() {
        val (s, port, p) = wifi()
        val (sch, sink) = p
        val first = listOf(ap(1, sch, freq = 2_412), ap(2, sch, freq = 5_180))
        port.batch = first
        port.listener!!(true)
        assertEquals(2, sink.rows.size)
        assertEquals(listOf(1, 2), sink.rows.map { it.emitter })
        assertEquals("5180", sink.rows[1].extra[WifiScanStream.FREQ])
        // The same batch again (another app's scan finished with nothing new): nothing added.
        port.listener!!(true)
        assertEquals(2, sink.rows.size)
        // Another app's scan, between our requests, with a fresh timestamp for one network.
        sch.advanceSeconds(10.0)
        port.batch = first + ap(1, sch, level = -50)
        port.listener!!(true)
        assertEquals(3, sink.rows.size)
        assertEquals(1, sink.rows.last().emitter)
        assertEquals(-50, sink.rows.last().rssiDbm)
        assertEquals(1, port.requestTimes.size)
        s.stop()
    }

    @Test fun noRequestWhileLocationIsOff() {
        val (s, port, p) = wifi { location = false }
        val (sch, sink) = p
        assertEquals(0, port.requestTimes.size)
        assertEquals(Status.LOCATION_OFF, sink.last())
        sch.advanceSeconds(62.0)
        assertEquals(0, port.requestTimes.size)
        port.location = true
        sch.advanceSeconds(31.0)
        assertEquals(1, port.requestTimes.size)
        assertEquals(Status.RECORDING, sink.last())
        s.stop()
    }

    @Test fun aBlockedScanNeverWatchesOrRequests() {
        val (s, port, p) = wifi { blocked = Status.PRECISE_NEEDED }
        assertEquals(Status.PRECISE_NEEDED, p.second.last())
        assertNull(port.listener)
        assertEquals(0, port.requestTimes.size)
        s.stop()
        assertEquals(0, port.unwatches)
    }

    @Test fun afterStopNothingIsRequestedOrWritten() {
        val (s, port, p) = wifi()
        val (sch, sink) = p
        val late = port.listener!!
        s.stop(); s.stop()
        assertEquals(1, port.unwatches)
        assertNull(port.listener)
        port.batch = listOf(ap(1, sch))
        late(true)
        sch.advanceSeconds(200.0)
        assertEquals(0, sink.rows.size)
        assertEquals(1, port.requestTimes.size)
        assertEquals(0, sch.pending())
    }

    @Test fun aResultsFailureWritesNothingAndTheStreamGoesOn() {
        val (s, port, p) = wifi { throwOnResults = true }
        val (sch, sink) = p
        port.listener!!(true)
        assertEquals(0, sink.rows.size)
        port.throwOnResults = false
        port.batch = listOf(ap(3, sch))
        port.listener!!(true)
        assertEquals(1, sink.rows.size)
        s.stop()
    }

    @Test fun leavingTheScreenDuringASessionReleasesTheReceiverAndTimer() {
        val (c, sch) = controller()
        val port = FakeWifiPort(sch)
        val table = EmitterTable(c.store.installKey, named = true)
        c.start(StartRequest(Scope.EVERYTHING_NEARBY, Provenance.LIVE, 60, emitters = table,
            streams = { listOf(WifiScanStream(port, it, table)) }))
        sch.advanceSeconds(2.0)
        c.screenStopped(changingConfigurations = false)
        val t0 = System.nanoTime()
        while (c.running && System.nanoTime() - t0 < 3_000_000_000L) Thread.sleep(10)
        assertFalse(c.running)
        assertNull(port.listener)
        assertEquals(1, port.unwatches)
        assertEquals(0, sch.pending())
        assertEquals(0, table.size.let { if (table.see("ble", fakeAddress(1), -50, sch.now, sch.wall) == null) 0 else 1 })
    }

    // Sessions with both scans

    private class Nearby(val c: SessionController, val sch: FakeScheduler, val id: String, val ble: FakeScanPort, val wifi: FakeWifiPort) {
        val dir: File get() = c.store.dir(id)
    }

    private fun nearby(root: File = newRoot(), named: Boolean = true, cap: Int = EmitterTable.MAX_DEVICES,
                       sizeLimit: Long = SessionController.SIZE_LIMIT_BYTES, seconds: Int = 60,
                       aliasOf: (SessionStore) -> (String) -> String? = { { ringOnly(it) } }): Nearby {
        val (c, sch) = controller(root, sizeLimit = sizeLimit)
        val ble = FakeScanPort()
        val wifi = FakeWifiPort(sch)
        val table = EmitterTable(c.store.installKey, named, cap)
        val r = c.start(StartRequest(Scope.EVERYTHING_NEARBY, Provenance.LIVE, seconds, emitters = table, streams = { s ->
            val h = BleScanHub(ble, s, aliasOf(c.store), emitters = table)
            listOf(h.scanStream, h.othersStream, WifiScanStream(wifi, s, table))
        })) as StartResult.Started
        return Nearby(c, sch, r.id, ble, wifi)
    }

    private fun Nearby.hit(address: String, rssi: Int, name: String? = null) =
        ble.onHit!!(ScanHit(address, rssi, sch.now, sch.wall, true, name, 0x0075))

    /** Ten seconds of four Bluetooth devices and the Ring, plus two Wi-Fi batches. */
    private fun Nearby.play() {
        for (sec in 0 until 10) {
            hit(TARGET, -55)
            for (i in 1..4) hit(fakeAddress(i), -70 - i, "Speaker $i")
            if (sec == 3 || sec == 7) {
                wifi.batch = listOf(WifiHit(fakeAddress(0x501), "Home Net", -48, 5_180, sch.now),
                    WifiHit(fakeAddress(0x502), "Cafe", -81, 2_437, sch.now))
                wifi.listener!!(true)
            }
            sch.advanceSeconds(1.0)
        }
        c.stop()
    }

    private fun texts(dir: File) = dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readText() }

    @Test fun everyDeviceGetsANumberAndNoFileHoldsAnAddressOrTheInstallKey() {
        val n = nearby()
        n.hit(fakeAddress(9), -60, "Tag 02:00:00:00:00:09")
        n.play()
        val files = texts(n.dir)
        for ((name, text) in files) {
            assertFalse("$name holds an address", macPattern.containsMatchIn(text))
            assertFalse("$name holds the install key", text.contains(n.c.store.installKey.hexForTests()))
        }
        val dev = EmitterFile.read(n.dir)
        assertEquals("ring, four devices, the tag and two networks", 8, dev.size)
        assertEquals((1..8).toList(), dev.map { it.number })
        assertEquals("ring", dev.first { it.alias != null }.alias)
        assertEquals("Tag [address]", dev.first { it.name?.startsWith("Tag") == true }.name)
        assertEquals(setOf("5", "2.4"), dev.filter { it.kind == "wifi" }.mapNotNull { it.band }.toSet())
        val scan = SessionReader.obs(n.dir, "ble_scan")
        assertTrue(scan.rows.all { (scan.cell(it, "emitter")!!.toInt()) in 1..8 })
        assertEquals(51, scan.rows.size)
        assertEquals(4, SessionReader.obs(n.dir, "wifi_scan").rows.size)
        assertEquals(Keys.MODE_NAMED, SessionReader.read(n.dir).props[Keys.IDENTIFIER_MODE])
    }

    @Test fun namedModeAddsNamesAndNothingElse() {
        val a = nearby(named = true).also { it.play() }
        val b = nearby(named = false).also { it.play() }
        val fa = texts(a.dir); val fb = texts(b.dir)
        assertEquals(fa.keys, fb.keys)
        for (name in fa.keys.filter { it.startsWith("obs_") }) assertEquals(name, fa[name], fb[name])
        assertEquals(Keys.MODE_NONE, SessionReader.read(b.dir).props[Keys.IDENTIFIER_MODE])
        for (text in fb.values) {
            assertFalse(text.contains("Speaker")); assertFalse(text.contains("Home Net")); assertFalse(text.contains("Cafe"))
        }
        val ea = Csv.parse(fa.getValue(FileNames.EMITTERS)); val eb = Csv.parse(fb.getValue(FileNames.EMITTERS))
        assertEquals(ea.header, eb.header)
        val skip = setOf("name", "key")
        for ((ra, rb) in ea.rows.zip(eb.rows)) {
            for (col in ea.header.filter { it !in skip }) assertEquals(col, ea.cell(ra, col), eb.cell(rb, col))
            assertEquals("", eb.cell(rb, "name"))
        }
        assertTrue(ea.rows.any { ea.cell(it, "name") == "Speaker 1" })
        assertTrue(NearbyDefaults.STORE_NAMES)
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    @Test fun anExportRenumbersFromOneAndHasNoKeysAndNamesOnlyWhenTicked() {
        val n = nearby().also { it.play() }
        val keys = EmitterFile.read(n.dir).map { it.key }
        assertTrue(keys.all { it.length == 16 })
        for (includeNames in listOf(false, true)) {
            val zip = unzip(ByteArrayOutputStream().also { SessionArchive.export(n.dir, it, includeNames) }.toByteArray())
            for ((name, text) in zip) for (k in keys) assertFalse("$name holds a key", text.contains(k))
            val e = Csv.parse(zip.getValue(FileNames.EMITTERS))
            assertFalse(e.has("key"))
            assertEquals(includeNames, e.has("name"))
            assertEquals((1..e.rows.size).map { it.toString() }, e.rows.map { e.cell(it, "emitter") })
            assertEquals(includeNames, zip.values.any { it.contains("Speaker 1") })
            val mode = Props.parse(zip.getValue(FileNames.PROPERTIES))[Keys.IDENTIFIER_MODE]
            assertEquals(if (includeNames) Keys.MODE_NAMED else Keys.MODE_NONE, mode)
        }
        // Numbers out of order in the file (an edited or imported session) come out from 1.
        val f = File(n.dir, FileNames.EMITTERS)
        val t = Csv.parse(f.readText())
        val swapped = StringBuilder(Csv.line(t.header))
        val renum = mapOf("1" to "9", "2" to "5")
        t.rows.filter { t.cell(it, "emitter") in renum }.forEach { r ->
            swapped.append(Csv.line(r.mapIndexed { i, v -> if (t.header[i] == "emitter") renum.getValue(v) else v }, setOf(EmitterTable.NAME_COLUMN)))
        }
        f.writeText(swapped.toString())
        val obs = File(n.dir, FileNames.obs("ble_scan"))
        val o = Csv.parse(obs.readText())
        obs.writeText(Csv.line(o.header) + o.rows.joinToString("") { r ->
            Csv.line(r.mapIndexed { i, v -> if (o.header[i] == "emitter") renum[v] ?: "77" else v })
        })
        val zip = unzip(ByteArrayOutputStream().also { SessionArchive.export(n.dir, it) }.toByteArray())
        val e = Csv.parse(zip.getValue(FileNames.EMITTERS))
        assertEquals(listOf("1", "2"), e.rows.map { e.cell(it, "emitter") })
        val ob = Csv.parse(zip.getValue(FileNames.obs("ble_scan")))
        assertEquals(setOf("1", "2", ""), ob.rows.map { ob.cell(it, "emitter") }.toSet())
    }

    @Test fun anExportOfASessionWithoutDevicesIsUnchanged() {
        val (c, sch) = controller()
        val dir = recordSimulated(c, sch, seconds = 5)
        val zip = unzip(ByteArrayOutputStream().also { SessionArchive.export(dir, it) }.toByteArray())
        for ((name, text) in zip) assertEquals(name, File(dir, name).readText(), text)
    }

    // T17: keys and pins

    @Test fun keysAreStableWithinAnInstallAndChangeAfterDeleteAll() {
        val root = newRoot()
        val k1 = SessionStore(root).installKey.key("ble", fakeAddress(7))
        assertEquals(k1, SessionStore(root).installKey.key("ble", fakeAddress(7).lowercase()))
        assertNotEquals(k1, SessionStore(root).installKey.key("wifi", fakeAddress(7)))
        val store = SessionStore(root)
        assertTrue(store.pins.pin(k1, "keys"))
        store.deleteAll(null)
        assertFalse(File(root, "install.key").exists())
        val k2 = SessionStore(root).installKey.key("ble", fakeAddress(7))
        assertNotEquals(k1, k2)
        assertTrue(store.pins.all().isEmpty())
        assertTrue(SessionStore(root).pins.all().isEmpty())
    }

    @Test fun pinRules() {
        val store = SessionStore(newRoot())
        val k = store.installKey.key("ble", fakeAddress(1))
        val k2 = store.installKey.key("ble", fakeAddress(2))
        assertFalse("ring is reserved", store.pins.pin(k, "ring"))
        assertFalse(store.pins.pin(k, "My Keys"))
        assertFalse(store.pins.pin("not-a-key", "keys"))
        assertTrue(store.pins.pin(k, "keys"))
        assertFalse("alias already used", store.pins.pin(k2, "keys"))
        assertEquals("keys", SessionStore(store.root).pins.aliasOf(k))
        store.pins.unpin(k)
        assertNull(SessionStore(store.root).pins.aliasOf(k))
    }

    @Test fun aPinnedDeviceGetsRowsInMyDevicesAndOthersDoNot() {
        val (c, sch) = controller()
        val scan = FakeScanPort()
        assertTrue(c.store.pins.pin(c.store.installKey.key("ble", fakeAddress(5)), "keys"))
        val aliasOf = PinMatcher(c.store.installKey, c.store.pins.all(), TARGET)
        val r = c.start(StartRequest(Scope.MY_DEVICES, Provenance.LIVE, 30, streams = { s ->
            val h = BleScanHub(scan, s, aliasOf); listOf(h.scanStream, h.othersStream)
        })) as StartResult.Started
        for (sec in 0 until 3) {
            for (i in 1..6) scan.onHit!!(ScanHit(fakeAddress(i), -70, sch.now, sch.wall, true, "Name $i"))
            scan.onHit!!(ScanHit(TARGET, -50, sch.now, sch.wall, true))
            sch.advanceSeconds(1.0)
        }
        c.stop()
        val dir = c.store.dir(r.id)
        val rows = SessionReader.obs(dir, "ble_scan")
        assertEquals(mapOf("keys" to 3, "ring" to 3), rows.rows.groupingBy { rows.cell(it, "alias")!! }.eachCount())
        assertFalse(File(dir, FileNames.EMITTERS).exists())
        assertEquals(Keys.MODE_NONE, SessionReader.read(dir).props[Keys.IDENTIFIER_MODE])
        for (f in dir.listFiles()!!) assertFalse(f.readText().contains("Name "))
        // The Ring's numbers and its G0 test use the Ring's rows only, not the pinned device's.
        val ring = ResultMath.compute(dir).streams.first { it.stream.kind == Kinds.BLE_SCAN }
        assertEquals(3, ring.whole!!.rows)
        assertEquals(-50, ring.whole!!.medianDbm)
        // With no pins and no Ring, nothing matches.
        assertTrue(PinMatcher(c.store.installKey, emptyMap(), null)(fakeAddress(5)) == null)
    }

    @Test fun theBluetoothTraceAndResultFollowTheRingInEverythingNearby() {
        val n = nearby()
        for (sec in 0 until 3) { n.hit(TARGET, -52); n.hit(fakeAddress(1), -90); n.sch.advanceSeconds(1.0) }
        val live = n.c.snapshot()!!.streams.first { it.kind == Kinds.BLE_SCAN }
        assertEquals(listOf(-52, -52, -52), live.trace.mapNotNull { it.rssiDbm })
        n.c.stop()
        val r = ResultMath.compute(n.dir).streams.first { it.stream.kind == Kinds.BLE_SCAN }
        assertEquals(3, r.whole!!.rows)
    }

    // Sizes

    @Test fun theDeviceTableNeverPushesASessionPastItsLimit() {
        val limit = 60_000L
        val n = nearby(cap = 40, sizeLimit = limit, seconds = 600)
        var i = 0
        while (n.c.running && i < 100_000) {
            n.hit(fakeAddress(i % 60), -60 - (i % 30), "Device with a fairly long advertised name number ${i % 60}")
            i++
            if (i % 50 == 0) n.sch.advanceSeconds(0.3)
        }
        n.sch.advanceSeconds(1.0)
        val t0 = System.nanoTime()
        while (n.c.running && System.nanoTime() - t0 < 3_000_000_000L) Thread.sleep(10)
        assertFalse(n.c.running)
        val props = SessionReader.read(n.dir).props
        assertEquals(StopReason.SIZE_LIMIT, props[Keys.STOP_REASON])
        assertEquals(Keys.STATE_COMPLETE, props[Keys.STATE])
        assertTrue(allFiles(n.dir).sumOf { File(n.dir, it).length() } <= limit)
        assertEquals(40, EmitterFile.read(n.dir).size)
        assertTrue(SessionReader.read(n.dir).streams.first { it.id == "ble_scan" }.dropped!! > 0)
    }

    @Test fun theDeviceTableIsOnDiskBeforeTheSessionEnds() {
        val n = nearby()
        for (i in 1..3) n.hit(fakeAddress(i), -60)
        n.sch.advanceSeconds(10.5)
        assertEquals(3, EmitterFile.read(n.dir).size)
        n.c.stop()
    }

    @Test fun aNewDeviceIsOnDiskBeforeAnyRowPointsAtIt() {
        val n = nearby()
        assertTrue(File(n.dir, FileNames.EMITTERS).isFile)
        for (i in 1..3) n.hit(fakeAddress(i), -60)
        n.sch.advanceSeconds(0.3)
        // Drained into rows but no tick yet: an interrupted session already has all three.
        assertEquals(listOf(1, 2, 3), EmitterFile.read(n.dir).map { it.number })
        n.sch.advanceSeconds(10.5)
        n.hit(fakeAddress(4), -61); n.hit(fakeAddress(1), -40)
        n.sch.advanceSeconds(0.3)
        val dev = EmitterFile.read(n.dir)
        assertEquals(listOf(1, 2, 3, 4), dev.map { it.number })
        // The file can hold a device twice between rewrites; readers and exports keep the last line.
        n.hit(fakeAddress(5), -62)
        n.sch.advanceSeconds(0.3)
        val raw = Csv.parse(File(n.dir, FileNames.EMITTERS).readText())
        val zip = unzip(ByteArrayOutputStream().also { SessionArchive.export(n.dir, it) }.toByteArray())
        val e = Csv.parse(zip.getValue(FileNames.EMITTERS))
        assertEquals((1..5).map { it.toString() }, e.rows.map { e.cell(it, "emitter") })
        assertTrue(raw.rows.size >= 5)
        n.c.stop()
        val end = Csv.parse(File(n.dir, FileNames.EMITTERS).readText())
        assertEquals(5, end.rows.size)
        assertEquals(-40, EmitterFile.read(n.dir).first { it.number == 1 }.bestDbm)
    }

    @Test fun aDeviceWrittenTwiceReadsAsItsLastLine() {
        val dir = newRoot().also { it.mkdirs() }
        val h = Csv.line(EmitterTable.COLUMNS)
        fun row(n: Int, rows: Int) = Csv.line(EmitterTable.COLUMNS.map { c -> when (c) {
            "emitter" -> n.toString(); "kind" -> "ble"; "rows" -> rows.toString(); "first_boot_ns" -> n.toString(); else -> "" } },
            setOf(EmitterTable.NAME_COLUMN))
        File(dir, FileNames.EMITTERS).writeText(h + row(1, 1) + row(2, 1) + row(1, 9))
        assertEquals(listOf(1 to 9L, 2 to 1L), EmitterFile.read(dir).map { it.number to it.rows })
    }

    @Test fun aReplayKeepsTheDeviceTable() {
        val n = nearby().also { it.play() }
        val req = Replay.request(n.dir)
        val r = n.c.start(req) as StartResult.Started
        n.sch.advanceSeconds(20.0)
        val t0 = System.nanoTime()
        while (n.c.running && System.nanoTime() - t0 < 3_000_000_000L) Thread.sleep(10)
        val replay = n.c.store.dir(r.id)
        assertEquals(EmitterFile.read(n.dir), EmitterFile.read(replay))
        assertEquals(Keys.MODE_NAMED, SessionReader.read(replay).props[Keys.IDENTIFIER_MODE])
        val ob = SessionReader.obs(replay, "ble_scan")
        assertTrue(ob.rows.isNotEmpty() && ob.rows.all { (ob.cell(it, "emitter")!!.toInt()) in 1..8 })
    }

    @Test fun namesAreCleanedAndCapped() {
        assertEquals("Tag [address]", Names.clean("Tag 02:00:00:00:00:09"))
        assertEquals("[address] x", Names.clean("0200000000AB x"))
        assertEquals("[address]", Names.clean("02-00-00-00-00-0a"))
        assertEquals("Tag_[address]", Names.clean("Tag_02:00:00:00:00:09"))
        assertEquals("[address]_x", Names.clean("020000000009_x"))
        assertEquals("0200000000ABC", Names.clean("0200000000ABC"))
        assertEquals("ab", Names.clean("a\u0000\nb"))
        assertNull(Names.clean("   "))
        assertTrue(Names.clean("é".repeat(200))!!.toByteArray().size <= Names.MAX_BYTES)
        assertTrue(Names.clean("😀".repeat(60))!!.toByteArray().size <= Names.MAX_BYTES)
    }
}
