package com.batterylab.personal

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobFinishGateTest {
    @Test fun finishRunsOnlyOnce() {
        val gate = JobFinishGate()
        var calls = 0
        assertTrue(gate.finish { calls++ })
        assertFalse(gate.finish { calls++ })
        assertEquals(1, calls)
    }

    @Test fun finishAfterStopDoesNotRun() {
        val gate = JobFinishGate()
        gate.stop()
        gate.stop()
        assertFalse(gate.finish { throw AssertionError("Stopped job finished") })
    }

    @Test fun stopAfterFinishIsHarmless() {
        val gate = JobFinishGate()
        var calls = 0
        assertTrue(gate.finish { calls++ })
        gate.stop()
        assertFalse(gate.finish { calls++ })
        assertEquals(1, calls)
    }

    @Test fun concurrentFinishesRunOnlyOnce() {
        val gate = JobFinishGate()
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map {
                workers.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    gate.finish { calls.incrementAndGet() }
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, calls.get())
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun aNewRunDoesNotReviveAStoppedRun() {
        val previous = JobFinishGate()
        previous.stop()
        val current = JobFinishGate()
        assertFalse(previous.finish { throw AssertionError("Old run finished") })
        assertTrue(current.finish {})
    }

    /** Runs the real listener wiring on [task] and returns every outcome it reported. */
    private fun outcomes(task: Task<Unit>): List<Boolean> {
        val seen = mutableListOf<Boolean>()
        WatchReporter.finishWhenDone(task, Runnable::run) { seen += it }
        return seen
    }

    @Test fun reporterFinishesSucceededFailedAndCancelledSends() {
        assertEquals(listOf(true), outcomes(Tasks.forResult(Unit)))
        assertEquals(listOf(false), outcomes(Tasks.forException(RuntimeException("no phone"))))
        assertEquals("A cancelled send must still finish the job", listOf(false), outcomes(Tasks.forCanceled()))
    }

    @Test fun reporterReportsASendThatSettlesLater() {
        val pending = TaskCompletionSource<Unit>()
        val seen = mutableListOf<Boolean>()
        WatchReporter.finishWhenDone(pending.task, Runnable::run) { seen += it }
        assertEquals(emptyList<Boolean>(), seen)
        pending.setResult(Unit)
        assertEquals(listOf(true), seen)
    }
}
