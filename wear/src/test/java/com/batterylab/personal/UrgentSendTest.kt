package com.batterylab.personal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neon deep review, Nov 26, 2026: a normal Data Layer item may wait up to 30 minutes, so the
 * "Send reading" button marks its item urgent; the 2-hour job does not (it would cost battery).
 */
class UrgentSendTest {
    private val dir = listOf(File("src/main/java/com/batterylab/personal"), File("wear/src/main/java/com/batterylab/personal")).first { it.isDirectory }
    private fun src(name: String) = File(dir, name).readText()

    @Test fun onlyTheButtonSendsUrgently() {
        val job = src("WatchBatteryJob.kt")
        assertTrue(job.contains("if (urgent) setUrgent()"))
        assertEquals(1, Regex("WatchReporter\\.send\\(this\\)").findAll(job).count())
        assertTrue(src("WatchActivity.kt").contains("WatchReporter.send(this@WatchActivity, urgent = true)"))
        // Background sends rely on the default, so it must stay non-urgent.
        assertTrue(job.contains("fun send(context: Context, urgent: Boolean = false,"))
        // The button is the only urgent caller anywhere in the Watch app.
        val urgentCalls = dir.listFiles()!!.filter { it.extension == "kt" }
            .sumOf { Regex("urgent = true").findAll(it.readText()).count() }
        assertEquals(1, urgentCalls)
    }
}
