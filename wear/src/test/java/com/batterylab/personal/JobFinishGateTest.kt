package com.batterylab.personal

import java.io.File
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

    @Test fun reporterFinishesCancelledSends() {
        val source = File("src/main/java/com/batterylab/personal/WatchBatteryJob.kt").readText()
            .substringAfter("fun send(").substringBefore("class WatchBatteryJob")
        assertTrue(source.contains("addOnCanceledListener { finish(false) }"))
    }
}
