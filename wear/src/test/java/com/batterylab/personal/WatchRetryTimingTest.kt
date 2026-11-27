package com.batterylab.personal

import android.app.job.JobInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neon deep review, Nov 26, 2026: a failed Watch sample was never retried (4.P2b), and the
 * one-argument setPeriodic let gaps run from almost nothing to almost 4 hours (4.P3c).
 */
class WatchRetryTimingTest {
    private val job = File("src/main/java/com/batterylab/personal/WatchBatteryJob.kt").readText()

    @Test fun onlyASureFailureIsRetried() {
        assertTrue(WatchReporter.needsRetry(SendOutcome.FAILED))
        assertFalse("A sent sample must not be sent again", WatchReporter.needsRetry(SendOutcome.SENT))
        assertFalse("A cancelled send may still land", WatchReporter.needsRetry(SendOutcome.CANCELLED))
    }

    @Test fun theJobPassesTheRetryDecisionAndAStoppedJobIsNotRetried() {
        assertTrue(job.contains("jobFinished(params, WatchReporter.needsRetry(outcome))"))
        val stop = job.substringAfter("override fun onStopJob").substringBefore("\n    }")
        assertTrue(stop.contains("return false"))
        assertFalse(stop.contains("return true"))
    }

    @Test fun samplesLandInTheLastFifteenMinutesOfEachPeriod() {
        val hour = 60L * 60L * 1000L
        assertEquals(2 * hour, WatchReporter.INTERVAL_MS)
        // The system raises a flex below 5 percent of the period, which would break the match in schedule().
        assertTrue(WatchReporter.FLEX_MS >= WatchReporter.INTERVAL_MS / 20)
        assertTrue(WatchReporter.FLEX_MS >= 5 * 60L * 1000L)
        assertTrue(WatchReporter.FLEX_MS < WatchReporter.INTERVAL_MS)
        val shortestGap = WatchReporter.INTERVAL_MS - WatchReporter.FLEX_MS
        val longestGap = WatchReporter.INTERVAL_MS + WatchReporter.FLEX_MS
        assertTrue(shortestGap >= 105 * 60L * 1000L && longestGap <= 135 * 60L * 1000L)
        assertTrue(job.contains(".setPeriodic(INTERVAL_MS, FLEX_MS)"))
    }

    @Test fun threeRetriesFitBeforeTheNextRegularRun() {
        val b = WatchReporter.BACKOFF_MS
        assertTrue("System minimum backoff is 10 seconds", b >= 10_000L)
        assertTrue(b + 2 * b + 4 * b <= WatchReporter.INTERVAL_MS)
        assertTrue(job.contains(".setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)"))
    }

    @Test fun aJobSavedByAnOlderVersionIsReplacedOnce() {
        val exp = JobInfo.BACKOFF_POLICY_EXPONENTIAL
        val i = WatchReporter.INTERVAL_MS
        assertTrue(WatchReporter.isCurrent(i, WatchReporter.FLEX_MS, WatchReporter.BACKOFF_MS, exp))
        // Old job: one-argument setPeriodic (flex = interval) and the default 30-second backoff.
        assertFalse(WatchReporter.isCurrent(i, i, 30_000L, exp))
        assertFalse(WatchReporter.isCurrent(i, WatchReporter.FLEX_MS, 30_000L, exp))
        assertFalse(WatchReporter.isCurrent(i, WatchReporter.FLEX_MS, WatchReporter.BACKOFF_MS, JobInfo.BACKOFF_POLICY_LINEAR))
        assertFalse(WatchReporter.isCurrent(30 * 60L * 1000L, WatchReporter.FLEX_MS, WatchReporter.BACKOFF_MS, exp))
    }
}
