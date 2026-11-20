package com.batterylab.rf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** T2 (replay and simulated), T3 (missing values), T4 (round trip), T5 (provenance), T6 (binary kind). */
class ReplayAndArchiveTest {
    private fun props(dir: File) = Props.parse(File(dir, FileNames.PROPERTIES).readText())

    /** (offset from session start, emitter, value, status) for every row of one stream. */
    private fun rows(dir: File, stream: String): List<List<String>> {
        val start = props(dir)[Keys.START_BOOT_NS]!!.toLong()
        val t = SessionReader.obs(dir, stream)
        return t.rows.map { r ->
            listOf((t.cell(r, "t_boot_ns")!!.toLong() - start).toString(), t.cell(r, "emitter")!!,
                t.cell(r, "rssi_dbm")!!, t.cell(r, "status")!!)
        }
    }

    private fun replay(c: SessionController, sch: FakeScheduler, source: File): File {
        val r = c.start(Replay.request(source)) as StartResult.Started
        sch.advanceSeconds(SessionController.MAX_DURATION_S.toDouble())
        assertFalse(c.running)
        return c.store.dir(r.id)
    }

    private fun exportImport(source: File, into: SessionStore): File {
        val zip = ByteArrayOutputStream().also { SessionArchive.export(source, it) }.toByteArray()
        val r = SessionArchive.import(ByteArrayInputStream(zip), into, 1_700_000_999_000L)
        assertTrue(r.toString(), r is ImportResult.Imported)
        return into.dir((r as ImportResult.Imported).id)
    }

    @Test fun simulatedWithTheSameSeedGivesIdenticalRows() {
        // Two independent runs on identical fake clocks.
        val (c1, s1) = controller(); val d1 = recordSimulated(c1, s1, seed = 42)
        val (c2, s2) = controller(); val d2 = recordSimulated(c2, s2, seed = 42)
        for (f in listOf("obs_sim_near.csv", "obs_sim_far.csv")) {
            assertArrayEquals(File(d1, f).readBytes(), File(d2, f).readBytes())
        }
        val (c3, s3) = controller(); val d3 = recordSimulated(c3, s3, seed = 43)
        assertFalse(File(d1, "obs_sim_near.csv").readBytes().contentEquals(File(d3, "obs_sim_near.csv").readBytes()))
    }

    @Test fun replayGivesTheSameSequenceTwiceAtTheRecordedOffsets() {
        val (c, sch) = controller()
        val src = recordSimulated(c, sch, seconds = 20, markers = listOf(5.0 to "near", 12.0 to "far"))
        val r1 = replay(c, sch, src)
        val r2 = replay(c, sch, src)
        for (s in listOf("sim_near", "sim_far")) {
            val orig = rows(src, s)
            assertTrue(orig.size > 10)
            assertEquals(rows(r1, s), rows(r2, s))
            val rep = rows(r1, s)
            assertEquals(orig.size, rep.size)
            orig.zip(rep).forEach { (o, p) ->
                assertTrue(Math.abs(o[0].toLong() - p[0].toLong()) <= 50_000_000)
                assertEquals(o.drop(1), p.drop(1))
            }
        }
        assertEquals(listOf("near", "far"), SessionReader.markers(r1).map { it.label })
        assertEquals(Provenance.SIMULATED.name, props(r1)[Keys.PROVENANCE])
        assertEquals(props(src)[Keys.ID], props(r1)[Keys.SOURCE_ID])
    }

    @Test fun replayOnARealClockKeepsEveryRowWithin50Ms() {
        val root = newRoot()
        val store = SessionStore(root)
        val src = store.dir("0000000000001").apply { mkdirs() }
        File(src, FileNames.PROPERTIES).writeText("format=1\nmin_reader=1\nid=0000000000001\nstart_boot_ns=1000000000\n" +
            "end_boot_ns=1800000000\nprovenance=LIVE\nscope=my_links\nstate=complete\n")
        File(src, FileNames.STREAMS).writeText("id,kind,unit,file\nwifi,wifi_link,dBm,obs_wifi.csv\n")
        val offsetsMs = listOf(0, 100, 250, 400, 600)
        File(src, "obs_wifi.csv").writeText("seq,t_boot_ns,t_wall_ms,emitter,rssi_dbm,status\n" +
            offsetsMs.mapIndexed { i, ms -> "${i + 1},${1_000_000_000L + ms * 1_000_000L},0,0,-${50 + i},ok\n" }.joinToString(""))
        val c = SessionController(store, { ThreadScheduler(SystemClockJvm) })
        val r = c.start(Replay.request(src)) as StartResult.Started
        val deadline = System.currentTimeMillis() + 5_000
        while (c.running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(c.running)
        val got = rows(store.dir(r.id), "wifi")
        assertEquals(offsetsMs.size, got.size)
        got.zip(offsetsMs).forEach { (row, ms) ->
            val diffMs = Math.abs(row[0].toLong() / 1_000_000 - ms)
            assertTrue("row at $ms ms replayed ${diffMs} ms off", diffMs <= 50)
        }
        assertEquals(Provenance.REPLAY.name, props(store.dir(r.id))[Keys.PROVENANCE])
    }

    @Test fun missingValuesStayEmptyThroughRecordExportImportAndReplay() {
        val (c, sch) = controller()
        val src = recordSimulated(c, sch, seconds = 60)
        fun empties(dir: File): Int {
            val t = SessionReader.obs(dir, "sim_far")
            val failed = t.rows.filter { t.cell(it, "status") != Status.OK }
            failed.forEach { assertEquals("", t.cell(it, "rssi_dbm")) }
            assertFalse(File(dir, "obs_sim_far.csv").readText().contains(",0,timeout"))
            return failed.size
        }
        val n = empties(src)
        assertTrue("the fixture needs failed reads", n > 0)
        val imported = exportImport(src, SessionStore(newRoot()))
        assertEquals(n, empties(imported))
        val (c2, s2) = controller()
        assertEquals(n, empties(replay(c2, s2, imported)))
    }

    @Test fun aReplayedSimulatedSessionExportedAndImportedIsStillSimulatedAndImported() {
        val (c, sch) = controller()
        val src = recordSimulated(c, sch, seconds = 10)
        val rep = replay(c, sch, src)
        val imported = exportImport(rep, SessionStore(newRoot()))
        val info = SessionReader.read(imported)
        assertEquals(Provenance.SIMULATED, info.provenance)
        assertTrue(info.imported)
        assertFalse(SessionReader.read(rep).imported)
    }

    @Test fun aBinaryStreamIsRecordedAndReplayedByteForByteWithNoTrace() {
        val (c, sch) = controller()
        var mock: MockBinaryStream? = null
        val r = c.start(StartRequest(Scope.MY_LINKS, Provenance.LIVE, 10, streams = {
            listOf(MockBinaryStream(it).also { m -> mock = m })
        })) as StartResult.Started
        sch.advanceSeconds(6.0)
        assertFalse(c.snapshot()!!.streams.single().hasTrace)
        assertEquals(5L, c.snapshot()!!.streams.single().rows)
        c.stop()
        val dir = c.store.dir(r.id)
        val recorded = File(dir, "bin_radar_points.dat").readBytes()
        assertArrayEquals(mock!!.sent.toByteArray(), recorded)
        val info = SessionReader.read(dir)
        assertEquals("mock_radar", info.streams.single().kind)
        assertFalse(info.streams.single().hasTrace)
        assertFalse(Kinds.hasTrace("mock_radar"))
        val rep = replay(c, sch, dir)
        assertArrayEquals(recorded, File(rep, "bin_radar_points.dat").readBytes())
        assertEquals(rows(dir, "radar_points").map { it.drop(1) }, rows(rep, "radar_points").map { it.drop(1) })
    }

    @Test fun everyFileRoundTripsWithUnknownKeysColumnsAndKinds() {
        val src = SessionStore(newRoot()).dir("1700000000000").apply { mkdirs() }
        val propsText = "format=1\nmin_reader=1\nid=1700000000000\nstart_boot_ns=10\nend_boot_ns=3000000010\n" +
            "provenance=LIVE\nscope=my_links\nstate=complete\nimported=false\nfuture_key=kept as is\n"
        File(src, FileNames.PROPERTIES).writeText(propsText)
        File(src, FileNames.STREAMS).writeText("id,kind,unit,file,nominal_rate_hz,rows,bytes,dropped,change_interval_ms,others_seen,future_col\n" +
            "wifi,wifi_link,dBm,obs_wifi.csv,1.000,2,0,0,,,x\ncsi,csi_frames,bytes,obs_csi.csv,0.000,0,0,0,,,y\n")
        File(src, FileNames.EVENTS).writeText("seq,t_boot_ns,t_wall_ms,type,stream,value,text\n1,20,0,marker,,,\"near, \"\"left\"\"\"\n")
        File(src, "obs_wifi.csv").writeText("seq,t_boot_ns,t_wall_ms,emitter,rssi_dbm,status,rssi_source,extra_future\n" +
            "1,100,0,0,-61,ok,wifi_info,a\n2,1000000100,0,0,,no_value,,b\n")
        File(src, "obs_csi.csv").writeText("seq,t_boot_ns,t_wall_ms,emitter,rssi_dbm,status,subcarriers\n1,200,0,0,,ok,64\n")
        File(src, "bin_csi.dat").writeBytes(ByteArray(300) { (it * 7).toByte() })
        File(src, "bin_unlisted.dat").writeBytes(byteArrayOf(0, 10, 13, 26, -1))
        val before = src.filesByName()

        val imported = exportImport(src, SessionStore(newRoot()))
        val after = imported.filesByName()
        assertEquals(before.keys, after.keys)
        for (name in before.keys - FileNames.PROPERTIES) assertArrayEquals(name, before.getValue(name), after.getValue(name))
        val p = props(imported)
        assertEquals("kept as is", p["future_key"])
        assertEquals("true", p[Keys.IMPORTED])
        assertEquals(propsText.lines().filter { it.isNotEmpty() }.map { it.substringBefore('=') }, p.keys.toList())

        // Readers keep unknown columns and kinds, and list a bin_ file that streams.csv does not.
        val info = SessionReader.read(imported)
        assertEquals(listOf("wifi", "csi", "unlisted"), info.streams.map { it.id })
        assertEquals(listOf(true, false, false), info.streams.map { it.hasTrace })
        assertEquals("x", info.streams[0].raw["future_col"])
        val obs = SessionReader.obs(imported, "wifi")
        assertEquals("a", obs.cell(obs.rows[0], "extra_future"))
        assertEquals("", obs.cell(obs.rows[1], "rssi_dbm"))
        assertEquals("near, \"left\"", SessionReader.markers(imported).single().label)

        // Writing what was parsed gives the same text back.
        assertEquals(propsText, Props.write(Props.parse(propsText)))
        val obsText = File(src, "obs_wifi.csv").readText()
        val t = Csv.parse(obsText)
        assertEquals(obsText, Csv.line(t.header) + t.rows.joinToString("") { Csv.line(it) })
    }
}
