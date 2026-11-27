package com.batterylab.personal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.P2c-1 (Neon deep review, Nov 26, 2026): the restore's Ring-setting check must hold the test lock. */
class AfterImportLockTest {
    private fun source(name: String) = listOf(
        File("src/main/java/com/batterylab/personal/$name.kt"),
        File("app/src/main/java/com/batterylab/personal/$name.kt")
    ).first { it.isFile }.readText()

    @Test fun afterImportReadsAndWritesTheRingSettingUnderTheTestLock() {
        val body = source("BatteryPollService").substringAfter("fun afterImport(").substringBefore("\n    }\n")
        val lock = body.indexOf("synchronized(experimentLock)")
        val read = body.indexOf("ringEnabled(context)")
        val write = body.indexOf("putBoolean(RING_ENABLED, false)")
        assertTrue("lock=$lock read=$read write=$write", lock >= 0 && read > lock && write > read)
    }

    @Test fun abandonChangesTheRingSettingUnderTheSameLock() {
        val abandon = source("MainActivity").substringAfter("private fun abandon(").substringBefore("\n    }\n")
        assertTrue(abandon.contains("synchronized(PollScheduler.experimentLock) {"))
        // Only the text between the lock's opening brace and its own closing brace counts.
        val locked = abandon.substringAfter("synchronized(PollScheduler.experimentLock) {").substringBefore("\n        }\n")
        assertTrue(locked.contains("PollScheduler.setRingEnabled("))
    }
}
