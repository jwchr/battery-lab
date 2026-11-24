package com.batterylab.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fixes for the RF findings in the Nov 24 main audit. Synthetic addresses only. */
class AuditRfFixesTest {
    @Test fun aFailedScanStopsTheEmptyPerSecondRows() {
        val port = FakeScanPort(); val sch = FakeScheduler()
        val h = BleScanHub(port, sch, ringOnly, key = ByteArray(32) { 7 })
        val others = RecordingSink()
        h.scanStream.start(RecordingSink()); h.othersStream.start(others)
        hitOther(port, sch)
        sch.advanceSeconds(3.0)
        port.onFailed!!(2)
        val atFailure = others.rows.size
        sch.advanceSeconds(30.0)
        assertEquals("no rows after the scan failed", atFailure, others.rows.size)
        h.scanStream.stop(); h.othersStream.stop()
    }

    private fun hitOther(port: FakeScanPort, sch: FakeScheduler) =
        port.onHit!!(ScanHit(fakeAddress(1), -70, sch.now, sch.wall, true))

    /** Near the size limit, a binary payload is written only together with the row that points at it. */
    @Test fun noBinaryBytesWithoutTheirRow() {
        val payload = ByteArray(40) { it.toByte() }
        val extra = (1..60).map { "c$it" }   // a wide row: well over 96 bytes
        var refused = 0
        var tried = 0
        for (limit in 200L..8_000L step 7) {
            val dir = newRoot()
            val w = SessionWriter(dir, mapOf(Keys.FORMAT to "1", Keys.START_BOOT_NS to "1"), limit)
            // Limits too small for the headers are refused up front; skip those.
            try { w.addStream("raw", Kinds.SIMULATED, "", 1.0, extra, binary = true) } catch (_: java.io.IOException) { continue }
            tried++
            var ok = 0
            while (ok < 50 && w.writeBinary("raw", 1_234_567_890_123_456_789L, 1_700_000_000_000L, payload)) ok++
            if (ok < 50) refused++
            val bin = File(dir, FileNames.bin("raw"))
            w.finish(emptyMap())
            val binLen = if (bin.exists()) bin.length() else 0L
            assertEquals("limit $limit: every payload byte has its row", ok * payload.size.toLong(), binLen)
        }
        assertTrue("the sweep covered writable limits", tried > 50)
        assertTrue("the sweep reached the size limit", refused > 0)
    }
}
