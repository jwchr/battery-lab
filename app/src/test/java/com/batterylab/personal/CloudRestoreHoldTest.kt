package com.batterylab.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A restore that gave up must not let a shorter local export overwrite the cloud copy. */
class CloudRestoreHoldTest {
    @Test fun shorterLocalHistoryKeepsTheHeldRestore() {
        assertFalse(CloudBackupAgent.replacesHeld(heldLines = 50_000, freshLines = 12))
    }

    @Test fun equalOrLongerLocalHistoryReplacesIt() {
        assertTrue(CloudBackupAgent.replacesHeld(heldLines = 50_000, freshLines = 50_000))
        assertTrue(CloudBackupAgent.replacesHeld(heldLines = 50_000, freshLines = 50_001))
    }
}
