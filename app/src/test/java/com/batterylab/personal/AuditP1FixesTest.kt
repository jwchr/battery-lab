package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream

/** Fixes for the P1 findings in the Nov 24 main audit. */
class AuditP1FixesTest {
    private fun gz(text: String): File = File.createTempFile("held", ".csv.gz").apply {
        deleteOnExit()
        GZIPOutputStream(outputStream()).bufferedWriter().use { it.write(text) }
    }

    @Test fun aCutOffHeldRestoreReadsAsUnreadableInsteadOfThrowing() {
        val good = gz("kind,device\n" + "reading,\"phone\",\"android\",2026-11-01T00:00:00.000Z,80\n".repeat(400))
        assertEquals(400, CloudBackupAgent.heldRowsOrNull(good))
        val cut = File.createTempFile("cut", ".csv.gz").apply { deleteOnExit() }
        cut.writeBytes(good.readBytes().copyOf(good.length().toInt() / 2))
        assertNull(CloudBackupAgent.heldRowsOrNull(cut))
        val notGzip = File.createTempFile("junk", ".csv.gz").apply { deleteOnExit(); writeText("not an archive") }
        assertNull(CloudBackupAgent.heldRowsOrNull(notGzip))
    }

    @Test fun onlyTheFourTestTypesImport() {
        for (m in BatteryStore.EXPERIMENT_MODES) assertTrue(ExperimentEndpoint.knownMode(m))
        assertFalse(ExperimentEndpoint.knownMode("a,b"))
        assertFalse(ExperimentEndpoint.knownMode("=CMD()"))
        assertFalse(ExperimentEndpoint.knownMode(""))
    }

    @Test fun aRestoreCannotAddASecondRunningTest() {
        assertFalse(ExperimentEndpoint.importAdds(endMs = null, anotherRunning = true))
        assertTrue(ExperimentEndpoint.importAdds(endMs = null, anotherRunning = false))
        assertTrue(ExperimentEndpoint.importAdds(endMs = 5L, anotherRunning = true))
    }

    @Test fun aSecondStartIsBlockedWhileTheFirstWaitsOnItsReading() {
        val t = 1_000_000L
        assertFalse(ExperimentEndpoint.startPending(0L, t))
        assertTrue(ExperimentEndpoint.startPending(t, t + 20_000))
        assertFalse(ExperimentEndpoint.startPending(t, t + ExperimentEndpoint.START_PENDING_MS))
        assertFalse(ExperimentEndpoint.startPending(t, t - 1))   // clock moved back: don't block forever
    }

    @Test fun aDamagedImportedEndIsRejectedButARunningTestIsNot() {
        assertTrue(ExperimentEndpoint.importEndValid(100L, null, null))
        assertTrue(ExperimentEndpoint.importEndValid(100L, 200L, 50))
        assertFalse(ExperimentEndpoint.importEndValid(100L, 200L, null))
        assertFalse(ExperimentEndpoint.importEndValid(100L, 100L, 50))
        assertFalse(ExperimentEndpoint.importEndValid(100L, 90L, 50))
    }

    @Test fun exportFallsBackToPlainWriteOnlyWhenTheProviderRejectsTruncate() {
        val modes = mutableListOf<String>()
        val out = openTruncating { modes += it; if (it == "wt") throw IllegalArgumentException("mode") else java.io.ByteArrayOutputStream() }
        assertTrue(out != null); assertEquals(listOf("wt", "w"), modes)
        modes.clear()
        openTruncating { modes += it; java.io.ByteArrayOutputStream() }
        assertEquals(listOf("wt"), modes)
        val other = runCatching { openTruncating { throw SecurityException("denied") } }.exceptionOrNull()
        assertTrue(other is SecurityException)
    }
}
