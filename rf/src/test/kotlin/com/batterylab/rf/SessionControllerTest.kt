package com.batterylab.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T1: the session state machine. Also R12 (nothing left running) and R18 (two probe lines). */
class SessionControllerTest {
    private fun props(dir: File) = Props.parse(File(dir, FileNames.PROPERTIES).readText())

    private fun simulated(seconds: Int = SessionController.DEFAULT_DURATION_S) =
        StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, seconds, streams = { SimulatedStream.pair(1, it) })

    private class Hooks : SessionHooks {
        val lines = mutableListOf<String>()
        override fun started(scope: Scope, plannedS: Int) { lines += ProbeText.start(scope, plannedS) }
        override fun ended(scope: Scope, actualS: Int) { lines += ProbeText.end(scope, actualS) }
    }

    @Test fun startRefusesASecondStartAndStopIsSafeTwice() {
        val hooks = Hooks()
        val (c, sch) = controller(hooks = hooks)
        val first = c.start(simulated()) as StartResult.Started
        assertTrue(c.start(simulated()) is StartResult.Refused)
        assertEquals(Keys.STATE_RECORDING, props(c.store.dir(first.id))[Keys.STATE])
        sch.advanceSeconds(5.0)
        c.stop()
        c.stop()
        val p = props(c.store.dir(first.id))
        assertEquals(Keys.STATE_COMPLETE, p[Keys.STATE])
        assertEquals(StopReason.STOP, p[Keys.STOP_REASON])
        assertEquals("1", p[Keys.FORMAT]); assertEquals("1", p[Keys.MIN_READER])
        assertFalse(c.running)
        // R12: no timer is left after the session.
        assertEquals(1, sch.shutdowns)
        assertEquals(0, sch.pending())
        // R18: exactly two probe lines, scope and duration only.
        assertEquals(listOf("RF session start: scope=my_links duration_s=90", "RF session end: scope=my_links duration_s=5"),
            hooks.lines)
        // A new session can start after the stop.
        assertTrue(c.start(simulated()) is StartResult.Started)
    }

    @Test fun defaultIs90SecondsAndTheCapIs10Minutes() {
        assertEquals(90, StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, streams = { emptyList() }).durationS)
        assertEquals(600, simulated(3600).durationS)
        val (c, sch) = controller()
        val r = c.start(simulated()) as StartResult.Started
        sch.advanceSeconds(89.9)
        assertTrue(c.running)
        sch.advanceSeconds(0.1)
        assertFalse(c.running)
        val p = props(c.store.dir(r.id))
        assertEquals(StopReason.TIME_LIMIT, p[Keys.STOP_REASON])
        assertEquals("90", p[Keys.DURATION_LIMIT_S])
    }

    @Test fun sizeLimitEndsTheSession() {
        val limit = 6_000L
        val (c, sch) = controller(sizeLimit = limit)
        val r = c.start(simulated()) as StartResult.Started
        sch.advanceSeconds(60.0)
        assertFalse(c.running)
        val dir = c.store.dir(r.id)
        assertEquals(StopReason.SIZE_LIMIT, props(dir)[Keys.STOP_REASON])
        val rows = dir.listFiles()!!.filter { it.name.startsWith("obs_") }.sumOf { it.length() }
        assertTrue(rows < limit)
        assertTrue("headers and metadata count too", dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } <= limit)
        assertEquals(16L * 1024 * 1024, SessionController.SIZE_LIMIT_BYTES)
    }

    /** Fails after three seconds through its sink. */
    private class FailingStream(private val sch: Scheduler) : Stream {
        override val id = "flaky"
        override val kind = Kinds.SIMULATED
        override val nominalRateHz = 1.0
        @Volatile var stops = 0
        override fun start(sink: StreamSink) {
            sch.schedule(3 * SEC) { sink.failed("radio_error") }
        }
        override fun stop() { stops++ }
    }

    @Test fun aStreamErrorEndsOnlyThatStream() {
        val (c, sch) = controller()
        var flaky: FailingStream? = null
        val r = c.start(StartRequest(Scope.MY_LINKS, Provenance.SIMULATED, 20, streams = {
            listOf(SimulatedStream("steady", 3, it), FailingStream(it).also { f -> flaky = f })
        })) as StartResult.Started
        sch.advanceSeconds(5.0)
        assertTrue(c.running)
        // The failed stream is stopped on its own thread.
        val t0 = System.nanoTime()
        while (flaky!!.stops == 0 && System.nanoTime() - t0 < 2_000_000_000L) Thread.sleep(5)
        assertEquals(1, flaky!!.stops)
        val rowsAt5 = c.snapshot()!!.streams.first { it.id == "steady" }.rows
        assertEquals(Status.ERROR, c.snapshot()!!.streams.first { it.id == "flaky" }.status)
        sch.advanceSeconds(5.0)
        assertTrue(c.snapshot()!!.streams.first { it.id == "steady" }.rows > rowsAt5)
        c.stop()
        val events = SessionReader.events(c.store.dir(r.id))
        assertTrue(events.rows.any { events.cell(it, "stream") == "flaky" && events.cell(it, "value") == Status.ERROR })
    }

    @Test fun anUnfinishedFolderAtStartupIsMarkedInterruptedAndKept() {
        val root = newRoot()
        val (c, _) = controller(root)
        val r = c.start(simulated()) as StartResult.Started
        // The process dies here: no stop. A new process builds a new controller.
        val (c2, _) = controller(root)
        val p = props(c2.store.dir(r.id))
        assertEquals(Keys.STATE_INTERRUPTED, p[Keys.STATE])
        assertEquals(StopReason.INTERRUPTED, p[Keys.STOP_REASON])
        assertTrue(c2.store.ids().contains(r.id))
    }

    @Test fun leavingTheScreenAndMarkersAreRecorded() {
        val (c, sch) = controller()
        assertFalse(c.marker("near"))
        val r = c.start(simulated()) as StartResult.Started
        sch.advanceSeconds(2.0)
        assertTrue(c.marker("near"))
        sch.advanceSeconds(2.0)
        c.stop(StopReason.LEFT_SCREEN)
        val dir = c.store.dir(r.id)
        assertEquals(StopReason.LEFT_SCREEN, props(dir)[Keys.STOP_REASON])
        assertEquals(listOf("near"), SessionReader.markers(dir).map { it.label })
        assertNotNull(c.snapshot())
        assertFalse(c.snapshot()!!.running)
        assertNull(c.activeId)
    }

    @Test fun statusChangesAreEventsAndRowsAreFlushedEachSecond() {
        val (c, sch) = controller()
        val r = c.start(simulated()) as StartResult.Started
        sch.advanceSeconds(3.0)
        val obs = File(c.store.dir(r.id), FileNames.obs("sim_near"))
        // Flushed by the one-second tick while the session is still running.
        assertTrue(Csv.parse(obs.readText()).rows.size >= 2)
        c.stop()
        val ev = SessionReader.events(c.store.dir(r.id))
        val statuses = ev.rows.filter { ev.cell(it, "type") == EventType.STATUS && ev.cell(it, "stream") == "sim_near" }
            .map { ev.cell(it, "value") }
        assertEquals(listOf(Status.STARTING, Status.RECORDING, Status.ENDED), statuses)
    }
}
