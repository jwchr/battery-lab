package com.batterylab.personal

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/**
 * Acceptance evidence: fake FIFO I/O and delayed queues, with monotonic milliseconds from receipt.
 * Plug holds through the settled save/skip, unplug and late logs hold through their single task,
 * and exceptions or an 8 s queue delay release exactly once. No Android or wall-clock sleeps.
 * Risks covered: early finish, stalled queues, stale callbacks, failed reads/posts, timer/read races.
 * Device lifetime and actual storage integration remain for the lead's Android checks; no UI changes.
 */
class PowerReceiverAsyncTest {
    private class Harness {
        // Nonzero origin catches accidental comparisons of uptime against a duration.
        var clock = 100_000L
        val io = ArrayDeque<() -> Unit>()
        val delayed = mutableListOf<Pair<Long, () -> Unit>>()
        val events = mutableListOf<String>()
        var finishes = 0
        var onRead: (String) -> Unit = {}
        var onLog: () -> Unit = {}

        fun start(
            connected: Boolean = true,
            current: () -> Boolean = { true },
            postIo: (() -> Unit) -> Unit = { io.addLast(it) },
            postDelay: (Long, () -> Unit) -> Unit = { ms, task -> delayed.add(clock + ms to task) },
        ) {
            PowerReceiverAsync.run(
                connected = connected,
                isCurrent = current,
                read = { trigger -> onRead(trigger); events.add(trigger) },
                logLate = { onLog(); events.add("late") },
                finish = { finishes++; events.add("finish") },
                postToIo = postIo,
                postDelayed = postDelay,
                now = { clock },
            )
        }

        fun runIo() { io.removeFirst().invoke() }

        fun advanceTo(elapsedMs: Long) {
            clock = 100_000 + elapsedMs
            while (true) {
                val next = delayed.minByOrNull { it.first } ?: return
                if (next.first > clock) return
                delayed.remove(next)
                next.second()
            }
        }
    }

    @Test fun plugFinishesOnlyAfterSettledRead() {
        val h = Harness()
        h.onRead = { assertEquals("Read must retain the broadcast", 0, h.finishes) }
        h.start()
        assertEquals(0, h.finishes)
        h.runIo()
        assertEquals(listOf("plug"), h.events)
        assertEquals(0, h.finishes)
        h.advanceTo(3_499)
        assertTrue(h.io.isEmpty())
        h.advanceTo(3_500)
        assertEquals(0, h.finishes)
        h.runIo()
        assertEquals(listOf("plug", "plug_settled", "finish"), h.events)
        h.advanceTo(8_000)
        assertEquals(1, h.finishes)
    }

    @Test fun throwingSettledReadStillFinishesExactlyOnce() {
        val h = Harness()
        h.onRead = { if (it == "plug_settled") error("synthetic read failure") }
        h.start()
        h.runIo()
        h.advanceTo(3_500)
        assertThrows(IllegalStateException::class.java) { h.runIo() }
        assertEquals(listOf("plug", "finish"), h.events)
        h.advanceTo(8_000)
        assertEquals(1, h.finishes)
    }

    @Test fun busyIoIsReleasedAtDeadlineAndQueuedSettledReadIsSkipped() {
        val h = Harness()
        h.start()
        h.runIo()
        h.advanceTo(3_500)
        assertEquals(1, h.io.size)
        h.advanceTo(8_000)
        // Must finish without draining the busy I/O queue.
        assertEquals(1, h.finishes)
        h.clock++
        h.runIo()
        assertEquals(listOf("plug", "finish"), h.events)
        assertEquals(1, h.finishes)
    }

    @Test fun workerChecksDeadlineEvenWhenMainTimeoutHasNotRun() {
        val h = Harness()
        h.start()
        h.runIo()
        h.advanceTo(3_500)
        // Advance only the clock, leaving the main timeout callback queued.
        h.clock = 108_001
        h.runIo()
        assertEquals(listOf("plug", "finish"), h.events)
        h.advanceTo(8_001)
        assertEquals(1, h.finishes)
    }

    @Test fun settledReadIsSkippedAtExactlySixSeconds() {
        val h = Harness()
        h.start()
        h.runIo()
        h.advanceTo(3_500)
        h.clock = 106_000
        h.runIo()
        assertEquals(listOf("plug", "finish"), h.events)
        h.advanceTo(8_000)
        assertEquals(1, h.finishes)
    }

    @Test fun settledReadMayStartJustBeforeDeadlineAndRetainsBroadcastUntilSaved() {
        val h = Harness()
        h.onRead = { trigger ->
            if (trigger == "plug_settled") {
                h.advanceTo(8_000)
                assertEquals("Timeout cannot finish a read already saving", 0, h.finishes)
            }
        }
        h.start()
        h.runIo()
        h.advanceTo(3_500)
        h.clock = 105_999
        h.runIo()
        assertEquals(listOf("plug", "plug_settled", "finish"), h.events)
        assertEquals(1, h.finishes)
    }

    @Test fun delayedMainCallbackAfterDeadlineDoesNotQueueSettledRead() {
        val h = Harness()
        h.start()
        h.runIo()
        // Run the deadline first, simulating callback reordering after a main-thread delay.
        h.clock = 108_001
        h.delayed.single { it.first == 108_000L }.second()
        h.advanceTo(8_001)
        assertTrue(h.io.isEmpty())
        assertEquals(listOf("plug", "finish"), h.events)
        assertEquals(1, h.finishes)
    }

    @Test fun timersUseReceiptTimeEvenWhenStateCheckTakesTime() {
        val h = Harness()
        h.start(current = { h.clock += 1_000; true })
        h.runIo()
        h.advanceTo(3_499)
        assertTrue(h.io.isEmpty())
        h.advanceTo(3_500)
        assertEquals(1, h.io.size)
        h.advanceTo(7_999)
        assertEquals(0, h.finishes)
        h.advanceTo(8_000)
        assertEquals(1, h.finishes)
        h.runIo()
        assertEquals(listOf("plug", "finish"), h.events)
        assertEquals(1, h.finishes)
    }

    @Test fun stuckIoStillFinishesUnplugAndLateEventsAtDeadline() {
        for (current in listOf(true, false)) {
            val h = Harness()
            h.start(connected = false, current = { current })
            h.advanceTo(7_999)
            assertEquals(0, h.finishes)
            h.advanceTo(8_000)
            assertEquals("I/O never ran, the deadline finished it", 1, h.finishes)
            h.clock++
            h.runIo()
            assertEquals(1, h.finishes)
        }
    }

    @Test fun unplugFinishesAfterSingleReadWithoutSettledWork() {
        val h = Harness()
        h.onRead = { assertEquals(0, h.finishes) }
        h.start(connected = false)
        assertEquals(0, h.finishes)
        assertEquals("Only the deadline is scheduled", listOf(108_000L), h.delayed.map { it.first })
        h.runIo()
        h.advanceTo(8_000)
        assertTrue(h.io.isEmpty())
        assertEquals(listOf("unplug", "finish"), h.events)
        assertEquals(1, h.finishes)
    }

    @Test fun lateEventFinishesAfterLogWithoutAnyRead() {
        for (connected in listOf(true, false)) {
            val h = Harness()
            h.onLog = { assertEquals(0, h.finishes) }
            h.start(connected = connected, current = { false })
            assertEquals(0, h.finishes)
            assertEquals("Only the deadline is scheduled", listOf(108_000L), h.delayed.map { it.first })
            h.runIo()
            h.advanceTo(8_000)
            assertEquals(listOf("late", "finish"), h.events)
            assertTrue(h.io.isEmpty())
            assertEquals(1, h.finishes)
        }
    }

    @Test fun throwingInitialReadFinishesAndCancelsSettledWork() {
        for (connected in listOf(true, false)) {
            val h = Harness()
            h.onRead = { error("synthetic initial read failure") }
            h.start(connected = connected)
            assertThrows(IllegalStateException::class.java) { h.runIo() }
            h.advanceTo(8_000)
            assertTrue(h.io.isEmpty())
            assertEquals(listOf("finish"), h.events)
            assertEquals(1, h.finishes)
        }
    }

    @Test fun throwingLateLogStillFinishesOnce() {
        val h = Harness()
        h.onLog = { error("synthetic log failure") }
        h.start(current = { false })
        assertThrows(IllegalStateException::class.java) { h.runIo() }
        assertEquals(1, h.finishes)
        h.advanceTo(8_000)
        assertEquals(1, h.finishes)
    }

    @Test fun throwingStateCheckFinishesBeforePropagatingFailure() {
        val h = Harness()
        assertThrows(IllegalStateException::class.java) {
            h.start(current = { error("synthetic state failure") })
        }
        assertEquals(1, h.finishes)
        assertTrue(h.io.isEmpty())
        h.advanceTo(8_000)
        assertEquals(1, h.finishes)
    }

    @Test fun rejectedInitialPostFinishesExactlyOnce() {
        val h = Harness()
        assertThrows(IllegalStateException::class.java) {
            h.start(postIo = { error("synthetic executor rejection") })
        }
        h.advanceTo(8_000)
        assertEquals(1, h.finishes)
    }

    @Test fun rejectedSettledPostFinishesExactlyOnce() {
        val h = Harness()
        var posts = 0
        h.start(postIo = { task ->
            posts++
            if (posts == 2) error("synthetic executor rejection")
            h.io.addLast(task)
        })
        h.runIo()
        assertThrows(IllegalStateException::class.java) { h.advanceTo(3_500) }
        assertEquals(listOf("plug", "finish"), h.events)
        h.advanceTo(8_000)
        assertEquals(1, h.finishes)
    }

    @Test fun rejectedDelayedPostsFinishExactlyOnceAndCancelQueuedWork() {
        for (rejectedPost in 1..2) {
            val h = Harness()
            var posts = 0
            assertThrows(IllegalStateException::class.java) {
                h.start(postDelay = { ms, task ->
                    posts++
                    if (posts == rejectedPost) error("synthetic timer rejection")
                    h.delayed.add(h.clock + ms to task)
                })
            }
            while (h.io.isNotEmpty()) h.runIo()
            h.advanceTo(8_000)
            assertEquals(listOf("finish"), h.events)
            assertEquals(1, h.finishes)
        }
    }
}
