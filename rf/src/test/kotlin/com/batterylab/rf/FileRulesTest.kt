package com.batterylab.rf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** T14 (file edge cases) and T7 (retention). */
class FileRulesTest {
    @Test fun aTornLastLineIsIgnored() {
        val t = Csv.parse("seq,t_boot_ns,rssi_dbm\n1,10,-60\n2,20,-6")
        assertEquals(1, t.rows.size)
        assertEquals("-60", t.cell(t.rows[0], "rssi_dbm"))
        assertEquals(mapOf("a" to "1"), Props.parse("a=1\nb=2"))
        // A torn line inside a quoted value is still torn.
        assertEquals(0, Csv.parse("text\n\"half\nwritten").rows.size)
    }

    @Test fun columnsAreFoundByNameAndUnknownOnesKept() {
        val t = Csv.parse("status,future,rssi_dbm,t_boot_ns\nok,x,-70,5\n")
        assertEquals("-70", t.cell(t.rows[0], "rssi_dbm"))
        assertEquals("x", t.cell(t.rows[0], "future"))
        assertEquals(null, t.cell(t.rows[0], "emitter"))
        assertEquals(listOf(Sample(5, -70, true)), ResultMath.samples(t))
    }

    @Test fun markerTextWithCommaQuoteLineBreakAndLeadingEqualsRoundTrips() {
        val labels = listOf("near, left", "say \"hi\"", "two\nlines", "=SUM(A1:A9)", "'quoted", "+1", "-far", "@x", "")
        val (c, sch) = controller()
        val r = c.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, 30, streams = { emptyList() })) as StartResult.Started
        for (l in labels) { sch.advanceSeconds(1.0); c.marker(l) }
        c.stop()
        val dir = c.store.dir(r.id)
        assertEquals(labels, SessionReader.markers(dir).map { it.label })
        val raw = File(dir, FileNames.EVENTS).readText()
        // No cell starts with a formula character, and free text is always quoted.
        assertFalse(raw.contains(",\"=")); assertTrue(raw.contains(",\"'=SUM"))
        assertTrue(raw.contains(",\"\"\n"))
    }

    private fun run(locale: Locale): Map<String, ByteArray> {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            val (c, sch) = controller()
            val dir = recordSimulated(c, sch, seconds = 15, markers = listOf(3.0 to "near"))
            return dir.filesByName()
        } finally { Locale.setDefault(saved) }
    }

    @Test fun filesAreIdenticalBytesUnderAGermanDefaultLocale() {
        val root = run(Locale.ROOT)
        val german = run(Locale.GERMANY)
        assertEquals(root.keys, german.keys)
        for (k in root.keys) assertArrayEquals(k, root.getValue(k), german.getValue(k))
        assertTrue(String(root.getValue(FileNames.STREAMS)).contains("1.000"))
    }

    @Test fun everyFileUsesLineFeedsAndHasAHeader() {
        val (c, sch) = controller()
        val dir = recordSimulated(c, sch, seconds = 5)
        for (f in dir.listFiles()!!) {
            val text = f.readText()
            assertFalse(f.name, text.contains('\r'))
            assertTrue(f.name, text.endsWith("\n"))
            if (f.name.endsWith(".csv")) assertTrue(f.name, text.lines()[0].startsWith(if (f.name == FileNames.STREAMS) "id," else "seq,"))
        }
    }

    // T7
    private fun fakeSession(store: SessionStore, wallMs: Long, bytes: Int = 10): String {
        val id = store.newId(wallMs)
        store.dir(id).mkdirs()
        File(store.dir(id), FileNames.PROPERTIES).writeText("format=1\nmin_reader=1\nid=$id\nstart_boot_ns=1\nstate=complete\n")
        File(store.dir(id), "obs_pad.csv").writeBytes(ByteArray(bytes))
        return id
    }

    @Test fun the101stSessionEvictsTheOldest() {
        assertEquals(100, SessionStore.MAX_SESSIONS)
        assertEquals(200L * 1024 * 1024, SessionStore.MAX_TOTAL_BYTES)
        val (c, sch) = controller()
        val ids = (0 until 100).map { fakeSession(c.store, 1_600_000_000_000L + it * 1000L) }
        val r = c.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, streams = { SimulatedStream.pair(1, it) })) as StartResult.Started
        sch.advanceSeconds(1.0); c.stop()
        val now = c.store.ids()
        assertEquals(100, now.size)
        assertFalse(now.contains(ids.first()))
        assertTrue(now.contains(ids[1]))
        assertTrue(now.contains(r.id))
    }

    @Test fun theTotalSizeCapEvictsByAge() {
        val store = SessionStore(newRoot())
        val ids = (0 until 5).map { fakeSession(store, 1_600_000_000_000L + it, bytes = 1000) }
        val per = store.sizeOf(ids[0])
        val evicted = store.enforceRetention(keep = setOf(ids[4]), maxBytes = per * 3)
        assertEquals(ids.take(2), evicted)
        assertEquals(ids.drop(2), store.ids())
    }

    @Test fun deleteOneAndDeleteAllSpareTheActiveSession() {
        val store = SessionStore(newRoot())
        val ids = (0 until 3).map { fakeSession(store, 1_600_000_000_000L + it) }
        assertFalse(store.delete(ids[0], activeId = ids[0]))
        assertFalse(store.delete("../x", activeId = null))
        assertTrue(store.delete(ids[1], activeId = null))
        store.deleteAll(activeId = ids[2])
        assertEquals(listOf(ids[2]), store.ids())
        // The session that kept recording has old device keys; it cannot be pinned from.
        assertTrue(store.keysStale(ids[2]))
        assertFalse(store.keysStale(null))
        store.deleteAll(activeId = null)
        assertFalse(store.keysStale(ids[2]))
    }
}
