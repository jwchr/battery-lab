package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A run that was stopped can't finish, or start a Ring read for, the run that replaced it. */
class AuditJobRunsTest {
    @Test fun lateWorkFromAStoppedRunIsIgnored() {
        val runs = JobRuns()
        val first = runs.start()
        assertTrue(runs.live(first))
        runs.stop()
        assertFalse(runs.live(first))
        val second = runs.start()
        assertTrue(runs.live(second))
        assertFalse("the first run's late work stays dead after the next run starts", runs.live(first))
    }

    @Test fun startsAndStopsOnManyThreadsNeverLoseACount() {
        val runs = JobRuns()
        val threads = 8
        val each = 50_000
        // Half the threads start runs and half stop them, so start() and stop() race each other.
        val pool = (1..threads).map { n ->
            Thread { repeat(each) { if (n % 2 == 0) runs.start() else runs.stop() } }
        }
        pool.forEach { it.start() }
        pool.forEach { it.join() }
        assertEquals("every start and stop counted, so no stopped run can come back to life",
            threads * each + 1, runs.start())
    }
}
