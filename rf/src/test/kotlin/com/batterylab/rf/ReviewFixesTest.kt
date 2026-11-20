package com.batterylab.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The PR 32 review fixes: shutdown ordering, bounded stops, binary columns, sparse buckets, retention. */
class ReviewFixesTest {
    private class GatedStream(override val id: String, private val gate: CountDownLatch, private val entered: CountDownLatch) : Stream {
        override val kind = Kinds.SIMULATED
        override val nominalRateHz = 1.0
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun start(sink: StreamSink) { entered.countDown(); gate.await(5, TimeUnit.SECONDS); calls += "start" }
        override fun stop() { calls += "stop" }
    }

    private class HangingStream(override val id: String, private val failAfter: Scheduler? = null) : Stream {
        override val kind = Kinds.SIMULATED
        override val nominalRateHz = 1.0
        override fun start(sink: StreamSink) { failAfter?.schedule(2 * SEC) { sink.failed("radio_error") } }
        override fun stop() { try { Thread.sleep(10_000) } catch (_: InterruptedException) { } }
    }

    @Test fun aStopDuringStartupStopsEachStreamAfterItsStartReturns() {
        val (c, _) = controller()
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        val s = GatedStream("gated", gate, entered)
        val starter = Thread { c.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, 20, streams = { listOf(s) })) }
        starter.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopper = Thread { c.stop() }
        stopper.start()
        Thread.sleep(100)
        gate.countDown()
        starter.join(3_000); stopper.join(3_000)
        assertEquals(listOf("start", "stop"), s.calls.toList())
        assertFalse(c.running)
    }

    @Test fun aSingleStreamThatHangsInStopStillEndsTheSessionIn3Seconds() {
        val (c, _) = controller()
        c.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, 20, streams = { listOf(HangingStream("stuck")) }))
        val t0 = System.nanoTime()
        c.stop()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 4_000)
        assertFalse(c.running)
        assertTrue("a new session can start", c.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, 20,
            streams = { SimulatedStream.pair(1, it) })) is StartResult.Started)
        c.stop()
    }

    @Test fun aFailedStreamWhoseStopHangsDoesNotHoldTheSessionScheduler() {
        val (c, sch) = controller()
        c.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, 10, streams = {
            listOf(SimulatedStream("steady", 3, it), HangingStream("stuck", it))
        }))
        val t0 = System.nanoTime()
        sch.advanceSeconds(5.0)
        assertTrue("the scheduler kept running", (System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertTrue(c.snapshot()!!.streams.first { it.id == "steady" }.rows >= 4)
        sch.advanceSeconds(6.0)
        assertFalse("the time limit still fires", c.running)
    }

    @Test fun aBinaryStreamAlwaysRecordsWhereItsBytesLanded() {
        val dir = newRoot()
        val w = SessionWriter(dir, mapOf(Keys.FORMAT to "1", Keys.START_BOOT_NS to "1"), SessionController.SIZE_LIMIT_BYTES)
        w.addStream("raw", Kinds.SIMULATED, "", 1.0, emptyList(), binary = true)
        w.addStream("plain", Kinds.SIMULATED, "dBm", 1.0, emptyList())
        assertTrue(w.writeBinary("raw", 10, 20, byteArrayOf(1, 2, 3)))
        assertTrue(w.writeBinary("plain", 10, 20, byteArrayOf(4)))
        assertTrue(w.finish(emptyMap()))
        val t = SessionReader.obs(dir, "raw")
        assertEquals("0", t.cell(t.rows.single(), Columns.BIN_OFFSET))
        assertEquals("3", t.cell(t.rows.single(), Columns.BIN_LENGTH))
        assertFalse("no payload without its references", java.io.File(dir, FileNames.bin("plain")).exists())
        assertEquals(1L, w.streamStates().first { it.id == "plain" }.dropped)
    }

    @Test fun aHugeImportedWindowNeedsNoDurationSizedMemory() {
        val st = ResultMath.stats(listOf(Sample(1, -50, true)), Window("all", 0, 1_000_000_000_000_000_000L))!!
        assertEquals(0, st.medianRowsPerBucket)
        assertEquals(1, st.rows)
        val neg = ResultMath.stats(listOf(Sample(Long.MIN_VALUE + 5, -50, true)), Window("all", Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(0, neg!!.medianRowsPerBucket)
    }

    @Test fun retentionNeverRemovesAProtectedSession() {
        val store = SessionStore(newRoot())
        val ids = (1..3).map { i -> store.dir("000000000000$i").apply { mkdirs(); resolve("x").writeText("x".repeat(100)) }.name }
        val evicted = store.enforceRetention(keep = setOf(ids[0], ids[2]), maxCount = 2)
        assertEquals(listOf(ids[1]), evicted)
        assertTrue(store.dir(ids[0]).exists())
    }
}
