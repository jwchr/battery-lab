package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A restore that gave up must not let a shorter local export overwrite the cloud copy. */
class CloudRestoreHoldTest {
    @Test fun shorterLocalHistoryKeepsTheHeldRestore() {
        assertFalse(CloudBackupAgent.replacesHeld(heldRows = 50_000, freshRows = 12))
    }

    @Test fun equalOrLongerLocalHistoryReplacesIt() {
        assertTrue(CloudBackupAgent.replacesHeld(heldRows = 50_000, freshRows = 50_000))
        assertTrue(CloudBackupAgent.replacesHeld(heldRows = 50_000, freshRows = 50_001))
    }

    private fun rows(csv: String) = CloudBackupAgent.historyRows(java.io.StringReader(csv).buffered())

    @Test fun onlyReadingAndExperimentRowsCount() {
        val header = "kind,device,mode_or_source,timestamp_utc,percentage\n"
        val held = header + "reading,\"phone\",\"android\",2026-11-01T00:00:00.000Z,80\n".repeat(5) +
            "experiment,\"ring:samsung_widget\",baseline,2026-11-01T00:00:00.000Z,90\n"
        // A shorter history padded with forecast rows and a multi-line journal note.
        val fresh = header + "reading,\"phone\",\"android\",2026-11-02T00:00:00.000Z,70\n".repeat(2) +
            "forecast,\"phone\",,2026-11-02T00:00:00.000Z,70\n".repeat(20) +
            "journal,\"phone\",,2026-11-02T00:00:00.000Z,,\"line one\nreading,not a row\nline three\"\n"
        assertEquals(6, rows(held))
        assertEquals(2, rows(fresh))
        assertFalse(CloudBackupAgent.replacesHeld(rows(held), rows(fresh)))
        assertEquals(0, rows(""))
    }
}
