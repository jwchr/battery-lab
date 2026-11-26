package com.batterylab.personal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
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
}
