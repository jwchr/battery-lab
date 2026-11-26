package com.batterylab.personal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupStreamsTest {
    private class Tracking(bytes: ByteArray) : FilterInputStream(ByteArrayInputStream(bytes)) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    private fun gzip(text: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    // The gzip magic bytes followed by a bad compression method: GZIPInputStream throws while reading the header.
    private val brokenHeader = byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x00, 0x00, 0x00)

    @Test fun aBrokenGzipHeaderStillClosesTheFileWhenSniffed() {
        val raw = Tracking(brokenHeader)
        try { BackupStreams.readMaybeGzip(raw) { it.readText() }; fail("a broken header must fail the restore") }
        catch (e: java.io.IOException) { /* the restore screen shows "Restore failed" */ }
        assertTrue("the opened file was left open", raw.closed)
    }

    @Test fun aBrokenGzipHeaderStillClosesTheFile() {
        val raw = Tracking(brokenHeader)
        try { BackupStreams.readGzip(raw) { it.readText() }; fail("a broken header must fail") }
        catch (e: java.io.IOException) { }
        assertTrue("the opened file was left open", raw.closed)
    }

    @Test fun readsGzipAndPlainTextAndClosesBoth() {
        val zipped = Tracking(gzip("a\nb\n"))
        assertEquals("a\nb\n", BackupStreams.readMaybeGzip(zipped) { it.readText() })
        assertTrue(zipped.closed)
        val plain = Tracking("x,y\n".toByteArray())
        assertEquals("x,y\n", BackupStreams.readMaybeGzip(plain) { it.readText() })
        assertTrue(plain.closed)
        assertEquals(2, BackupStreams.readGzip(Tracking(gzip("a\nb\n"))) { it.lineSequence().count() })
    }

    @Test fun anEmptyOrOneByteFileReadsAsPlainText() {
        assertEquals("", BackupStreams.readMaybeGzip(Tracking(ByteArray(0))) { it.readText() })
        assertEquals("\u001f", BackupStreams.readMaybeGzip(Tracking(byteArrayOf(0x1f))) { it.readText() })
    }

    private class FullDisk : OutputStream() {
        var closed = false
        override fun write(b: Int) = throw IOException("No space left on device")
        override fun close() { closed = true }
    }

    @Test fun aFullDiskWhileWritingTheGzipHeaderStillClosesTheFile() {
        val raw = FullDisk()
        try { BackupStreams.writeGzip(raw) { it.write("a\n") }; fail("a full disk must fail the backup") }
        catch (e: IOException) { }
        assertTrue("the staging file was left open", raw.closed)
    }

    @Test fun writesGzipThatReadsBack() {
        val out = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        BackupStreams.writeGzip(out) { it.write("a\nb\n") }
        assertTrue(out.closed)
        assertEquals("a\nb\n", BackupStreams.readGzip(Tracking(out.toByteArray())) { it.readText() })
    }
}
