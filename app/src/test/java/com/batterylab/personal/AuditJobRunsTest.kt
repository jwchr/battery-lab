package com.batterylab.personal

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
}
