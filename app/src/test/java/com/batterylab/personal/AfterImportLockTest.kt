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

    /** The text inside the block that opens at [marker], up to its own matching closing brace. */
    private fun lockedBlock(text: String, marker: String): String {
        val open = text.indexOf(marker).also { assertTrue("missing $marker", it >= 0) } + marker.length - 1
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(open + 1, i) }
        }
        error("unclosed block after $marker")
    }

    @Test fun afterImportReadsAndWritesTheRingSettingUnderTheTestLock() {
        val body = source("BatteryPollService").substringAfter("fun afterImport(")
        val locked = lockedBlock(body, "synchronized(experimentLock) {")
        for (call in listOf("ringEnabled(context)", "activeExperiment()", "putBoolean(RING_ENABLED, false)"))
            assertTrue("$call must sit inside the one locked block", locked.contains(call))
    }

    @Test fun abandonChangesTheRingSettingUnderTheSameLock() {
        val abandon = source("MainActivity").substringAfter("private fun abandon(")
        val locked = lockedBlock(abandon, "synchronized(PollScheduler.experimentLock) {")
        for (call in listOf("store.abandonExperiment(", "PollScheduler.setRingEnabled("))
            assertTrue("$call must sit inside the one locked block", locked.contains(call))
    }
}
