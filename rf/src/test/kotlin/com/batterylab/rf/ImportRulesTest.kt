package com.batterylab.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** T11: import rejects each bad archive and writes nothing outside its staging folder, which is gone afterward. */
class ImportRulesTest {
    private val propsText = "format=1\nmin_reader=1\nid=1\nstart_boot_ns=1\nprovenance=LIVE\nscope=my_links\nstate=complete\n"
    private val valid = listOf(FileNames.PROPERTIES to propsText.toByteArray(),
        FileNames.STREAMS to "id,kind\nwifi,wifi_link\n".toByteArray(),
        "obs_wifi.csv" to "seq,t_boot_ns,t_wall_ms,emitter,rssi_dbm,status\n1,1,0,0,-60,ok\n".toByteArray())

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { bo ->
        ZipOutputStream(bo).use { z -> for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
    }.toByteArray()

    /** Imports [bytes] into a store whose root also holds a canary folder, and checks nothing leaked. */
    private fun rejected(bytes: ByteArray, expect: String? = null, input: InputStream = ByteArrayInputStream(bytes)): String {
        val parent = newRoot()
        val root = File(parent, "rf").apply { mkdirs() }
        val store = SessionStore(root)
        val existing = store.newId(1_600_000_000_000L).also { File(store.dir(it).apply { mkdirs() }, FileNames.PROPERTIES).writeText(propsText) }
        val before = allFiles(parent)
                val r = SessionArchive.import(input, store, 1_700_000_000_000L)
        assertTrue("expected a rejection, got $r", r is ImportResult.Rejected)
        assertEquals(before, allFiles(parent))
        assertEquals(listOf(existing), store.ids())
        assertFalse("the staging folder is gone", store.stagingDir.exists() && store.stagingDir.list()!!.isNotEmpty())
        val reason = (r as ImportResult.Rejected).reason
        if (expect != null) assertTrue(reason, reason.contains(expect))
        return reason
    }

    @Test fun aValidArchiveImportsAsANewMarkedSession() {
        val store = SessionStore(newRoot())
        val r = SessionArchive.import(ByteArrayInputStream(zip(valid)), store, 1_700_000_000_000L)
        assertTrue(r.toString(), r is ImportResult.Imported)
        val info = SessionReader.read(store.dir((r as ImportResult.Imported).id))
        assertTrue(info.imported)
        assertEquals(r.id, info.id)
        assertFalse(store.stagingDir.exists() && store.stagingDir.list()!!.isNotEmpty())
    }

    @Test fun badNamesRejectTheWholeArchive() {
        for (name in listOf("../obs_x.csv", "..", "obs_..csv", "/obs_x.csv", "/tmp/obs_x.csv", "sub/obs_x.csv",
            "sub\\obs_x.csv", "obs_x\u0000.csv", "notes.txt", "obs_x.dat", "bin_x.csv", "obs_UPPER.csv",
            "obs_${"a".repeat(25)}.csv", "obs_.csv", "session.properties.tmp")) {
            rejected(RawZip().entry(FileNames.PROPERTIES, propsText.toByteArray()).entry(name, "x\n".toByteArray()).bytes(),
                "name")
        }
        rejected(zip(valid + ("folder/" to ByteArray(0))), "folder")
    }

    @Test fun aDuplicateNameRejects() {
        rejected(RawZip().entry(FileNames.PROPERTIES, propsText.toByteArray()).entry("obs_a.csv", "1\n".toByteArray())
            .entry("obs_a.csv", "2\n".toByteArray()).bytes(), "twice")
    }

    @Test fun moreThan64EntriesRejects() {
        val many = (1..64).map { "obs_s$it.csv" to "seq\n".toByteArray() }
        rejected(zip(listOf(FileNames.PROPERTIES to propsText.toByteArray()) + many), "64 entries")
    }

    @Test fun anEntryPast32MbRejects() {
        rejected(zip(valid + ("bin_big.dat" to ByteArray(32 * 1024 * 1024 + 1))), "32 MB")
    }

    @Test fun aTotalPast64MbRejects() {
        val thirty = ByteArray(30 * 1024 * 1024)
        rejected(zip(valid + listOf("bin_a.dat" to thirty, "bin_b.dat" to thirty, "bin_c.dat" to thirty)), "64 MB")
    }

    @Test fun aFalseSizeFieldRejects() {
        val data = ByteArray(5000) { (it % 50).toByte() }
        // The header claims 100 bytes; the bytes read say otherwise.
        rejected(RawZip().entry(FileNames.PROPERTIES, propsText.toByteArray())
            .entry("bin_x.dat", data, deflate = true, declaredSize = 100).bytes())
        // A size field that claims little cannot sneak a large entry past the 32 MB count.
        rejected(RawZip().entry(FileNames.PROPERTIES, propsText.toByteArray())
            .entry("bin_x.dat", ByteArray(33 * 1024 * 1024), deflate = true, declaredSize = 10).bytes(), "32 MB")
    }

    @Test fun anUnsupportedMinReaderRejects() {
        val newer = propsText.replace("min_reader=1", "min_reader=2").toByteArray()
        rejected(zip(listOf(FileNames.PROPERTIES to newer) + valid.drop(1)), "newer")
    }

    @Test fun anArchiveWithoutPropertiesOrNotAZipRejects() {
        rejected(zip(valid.drop(1)), "session.properties")
        rejected("not a zip at all".toByteArray())
        rejected(zip(listOf(FileNames.PROPERTIES to "format=1\n".toByteArray())))
    }

    @Test fun aFileLargerThan64MbIsRefusedWhileCopying() {
        val total = 64L * 1024 * 1024 + 1
        val big = object : InputStream() {
            var left = total
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt(); left -= n; return n
            }
        }
        rejected(ByteArray(0), "64 MB", big)
    }
}
