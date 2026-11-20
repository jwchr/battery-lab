package com.batterylab.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Ring port that records every call. Synthetic values only. */
class FakeRingPort : RingLinkPort {
    val calls = mutableListOf<String>()
    var bt = true
    var permission = true
    var test = false
    var gateFree = true
    var gateTaken = 0
    var gateReleased = 0
    var connectStarts = true
    var events: RingLinkEvents? = null
    var lastLink = 0
    var reads = 0
    var outstandingReads = 0
    var maxOutstanding = 0
    var btListener: ((Boolean) -> Unit)? = null
    var throwOnRead = false

    override fun open() { calls += "open" }
    override fun bluetoothOn() = bt
    override fun hasPermission() = permission
    override fun testRunning() = test
    override fun tryTakeGate(): Boolean {
        if (!gateFree) return false
        gateFree = false; gateTaken++; return true
    }
    override fun releaseGate() { gateFree = true; gateReleased++ }
    override fun connect(link: Int, events: RingLinkEvents): Boolean {
        calls += "connect"; this.events = events; lastLink = link; return connectStarts
    }
    override fun disconnect() { calls += "disconnect" }
    override fun close() { calls += "close"; outstandingReads = 0 }
    override fun readRssi(): Boolean {
        if (throwOnRead) throw SecurityException("revoked")
        calls += "read"; reads++; outstandingReads++
        maxOutstanding = maxOf(maxOutstanding, outstandingReads)
        return true
    }
    override fun watchBluetooth(listener: (Boolean) -> Unit) { calls += "watch"; btListener = listener }
    override fun unwatchBluetooth() { calls += "unwatch"; btListener = null }
    override fun shutdown() { calls += "shutdown" }

    fun up() = events!!.connectionChanged(lastLink, true, 0)
    fun down(status: Int = 8) = events!!.connectionChanged(lastLink, false, status)
    fun answer(value: Int, link: Int = lastLink, status: Int = 0) { outstandingReads = maxOf(0, outstandingReads - 1); events!!.rssi(link, value, status) }
    fun count(name: String) = calls.count { it == name }
}

/** Collects what a stream reports. */
class RecordingSink : StreamSink {
    val rows = mutableListOf<Obs>()
    val statuses = mutableListOf<Pair<String, String>>()
    var dropped = 0
    var othersSeen = 0
    override fun observe(obs: Obs) { rows += obs }
    override fun binary(tBootNs: Long, tWallMs: Long, bytes: ByteArray) { }
    override fun status(status: String, detail: String) {
        if (statuses.lastOrNull() != status to detail) statuses += status to detail
    }
    override fun dropped(count: Int) { dropped += count }
    override fun othersSeen(count: Int) { othersSeen = count }
    override fun failed(reason: String) { statuses += Status.ERROR to reason }
    fun last() = statuses.last().first
}

/** T12 (ring link with a fake port and clock) and T16 (the gate and battery tests). */
class RingLinkTest {
    private fun setup(configure: FakeRingPort.() -> Unit = {}): Triple<RingLinkStream, FakeRingPort, Pair<FakeScheduler, RecordingSink>> {
        val port = FakeRingPort().apply(configure)
        val sch = FakeScheduler()
        val sink = RecordingSink()
        val s = RingLinkStream(port, sch)
        s.start(sink)
        return Triple(s, port, sch to sink)
    }

    @Test fun noReadBeforeConnectedAndNeverTwoOutstanding() {
        val (s, port, p) = setup()
        val (sch, sink) = p
        assertEquals(1, port.count("connect"))
        sch.advanceSeconds(5.0)
        assertEquals("no read before the connected callback", 0, port.reads)
        port.up()
        assertEquals(Status.RECORDING, sink.last())
        // Answer only every other read: reads must still never overlap.
        for (i in 1..20) {
            sch.advanceSeconds(1.0)
            if (i % 2 == 0 && port.outstandingReads > 0) port.answer(-60 - i)
        }
        assertEquals(1, port.maxOutstanding)
        assertTrue(sink.rows.any { it.status == Status.OK && it.rssiDbm != null })
        s.stop()
    }

    @Test fun aReadWithNoCallbackIsATimeoutAtThreeSecondsAndThreeCauseAReconnect() {
        val (s, port, p) = setup()
        val (sch, sink) = p
        port.up()
        sch.advanceSeconds(1.0)
        assertEquals(1, port.reads)
        sch.advanceSeconds(2.9)
        assertTrue(sink.rows.none { it.status == RingLinkStream.READ_TIMEOUT })
        sch.advanceSeconds(0.2)
        val timeout = sink.rows.single { it.status == RingLinkStream.READ_TIMEOUT }
        assertEquals("a failed read keeps an empty value", null, timeout.rssiDbm)
        // Two more timeouts in a row (reads at 4 s and 7 s): the link counts as lost at 10 s.
        sch.advanceSeconds(5.8)
        assertEquals(2, sink.rows.count { it.status == RingLinkStream.READ_TIMEOUT })
        assertEquals(0, port.count("disconnect"))
        sch.advanceSeconds(0.2)
        assertEquals(3, sink.rows.count { it.status == RingLinkStream.READ_TIMEOUT })
        assertEquals(1, port.count("disconnect"))
        assertEquals(Status.NOT_CONNECTED, sink.last())
        sch.advanceSeconds(1.8)
        assertEquals("waits up to 2 seconds before close", 0, port.count("close"))
        sch.advanceSeconds(0.3)
        assertEquals(1, port.count("close"))
        assertEquals("gate released after close", 1, port.gateReleased)
        sch.advanceSeconds(9.7)
        assertEquals("pauses 10 seconds", 1, port.count("connect"))
        sch.advanceSeconds(0.2)
        assertEquals("then tries again", 2, port.count("connect"))
        s.stop()
    }

    @Test fun theLateAnswerToATimedOutReadIsNeverStoredAsANewSample() {
        val (s, port, p) = setup()
        val (sch, sink) = p
        port.up()
        sch.advanceSeconds(1.0)            // read 1
        sch.advanceSeconds(3.1)            // read 1 times out
        assertEquals(1, sink.rows.count { it.status == RingLinkStream.READ_TIMEOUT })
        sch.advanceSeconds(1.0)
        assertEquals("no second read while the first may still answer", 1, port.reads)
        port.answer(-40)                   // read 1's answer, late
        assertTrue(sink.rows.none { it.rssiDbm == -40 })
        assertTrue(sink.statuses.any { it.second == "late callbacks: 1" })
        sch.advanceSeconds(1.0)            // read 2
        assertEquals(2, port.reads)
        port.answer(-61)
        assertEquals(-61, sink.rows.last().rssiDbm)
        assertEquals(1, port.maxOutstanding)
        s.stop()
    }

    @Test fun aConnectAttemptEndsAt15Seconds() {
        val (s, port, p) = setup()
        val (sch, _) = p
        sch.advanceSeconds(14.9)
        assertEquals(0, port.count("disconnect"))
        sch.advanceSeconds(0.2)
        assertEquals(1, port.count("disconnect"))
        port.down(0)   // the disconnect callback arrives: close at once, no 2-second wait
        assertEquals(1, port.count("close"))
        assertEquals(1, port.gateReleased)
        s.stop()
    }

    @Test fun stopClosesWithin3SecondsWhenNoDisconnectCallbackArrives() {
        val (s, port, _) = setup()
        port.up()
        val t0 = System.nanoTime()
        s.stop()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("stop took $ms ms", ms < 3_000)
        assertEquals(listOf("disconnect", "close"), port.calls.filter { it == "disconnect" || it == "close" })
        assertEquals(1, port.gateTaken)
        assertEquals(1, port.gateReleased)
        assertTrue(port.calls.indexOf("close") < port.calls.indexOf("shutdown"))
        assertTrue(port.calls.contains("unwatch"))
        s.stop()
        assertEquals("stop twice is safe and releases once", 1, port.gateReleased)
    }

    @Test fun stopReturnsAsSoonAsTheDisconnectCallbackArrives() {
        val (s, port, _) = setup()
        port.up()
        val l = port.lastLink
        val t = Thread { Thread.sleep(100); port.events!!.connectionChanged(l, false, 0) }
        t.start()
        val t0 = System.nanoTime()
        s.stop()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500)
        assertEquals(1, port.count("close"))
        t.join()
    }

    @Test fun callbacksFromAnOldLinkAreIgnoredAndLateOnesCounted() {
        val (s, port, p) = setup()
        val (sch, sink) = p
        port.up()
        val oldLink = port.lastLink
        port.down()                       // lost: closing
        sch.advanceSeconds(13.0)          // closed, paused, connecting again
        assertEquals(2, port.count("connect"))
        assertTrue(port.lastLink != oldLink)
        port.events!!.connectionChanged(oldLink, true, 0)
        assertFalse("old link's connected callback ignored", sink.last() == Status.RECORDING)
        port.events!!.rssi(oldLink, -40, 0)
        assertTrue(sink.rows.none { it.rssiDbm == -40 })
        assertTrue(sink.statuses.any { it.second.startsWith("late callbacks") })
        s.stop()
        assertEquals(port.gateTaken, port.gateReleased)
    }

    @Test fun gateIsReleasedExactlyOnceOnEveryPath() {
        // Connect that never starts.
        setup { connectStarts = false }.let { (s, port, _) ->
            s.stop(); assertEquals(1, port.gateTaken); assertEquals(1, port.gateReleased); assertEquals(0, port.count("close"))
        }
        // Permission revoked mid-link.
        setup().let { (s, port, p) ->
            port.up(); port.throwOnRead = true
            p.first.advanceSeconds(1.0)
            assertEquals(Status.PERMISSION_NEEDED, p.second.last())
            assertEquals(1, port.count("close"))
            p.first.advanceSeconds(30.0)
            assertEquals("no retry without permission", 1, port.count("connect"))
            s.stop()
            assertEquals(1, port.gateReleased)
        }
        // Bluetooth off mid-link closes at once.
        setup().let { (s, port, p) ->
            port.up()
            port.btListener!!(false)
            assertEquals(1, port.count("close")); assertEquals(1, port.gateReleased)
            assertEquals(Status.OFF, p.second.last())
            s.stop()
            assertEquals(1, port.gateReleased)
        }
    }

    @Test fun bluetoothReturningRestartsOnceAfter10Seconds() {
        val (s, port, p) = setup()
        val (sch, _) = p
        port.btListener!!(false)
        port.btListener!!(true)
        port.btListener!!(true)            // a repeated "on" does not add a restart
        sch.advanceSeconds(9.9)
        assertEquals(1, port.count("connect"))
        sch.advanceSeconds(0.2)
        assertEquals(2, port.count("connect"))
        port.up()                          // connected again: no further restarts
        sch.advanceSeconds(8.0)
        assertEquals(2, port.count("connect"))
        s.stop()
    }

    @Test fun stopDuringTheRestartDelayCancelsIt() {
        val (s, port, p) = setup()
        port.btListener!!(false)
        port.btListener!!(true)
        s.stop()
        p.first.advanceSeconds(30.0)
        assertEquals(1, port.count("connect"))
        assertEquals(null, port.btListener)
    }

    @Test fun withABatteryTestRunningItIsBlockedAndNeverConnects() {
        val (s, port, p) = setup { test = true }
        val (sch, sink) = p
        assertEquals(Status.BLOCKED_BY_TEST, sink.last())
        sch.advanceSeconds(60.0)
        assertEquals(0, port.count("connect"))
        assertEquals(0, port.gateTaken)
        s.stop()
    }

    @Test fun aTestStartingMidLinkClosesTheLink() {
        val (s, port, p) = setup()
        val (sch, sink) = p
        port.up()
        port.test = true
        sch.advanceSeconds(5.5)
        assertEquals(1, port.count("disconnect"))
        sch.advanceSeconds(15.0)
        assertEquals(1, port.gateReleased)
        assertEquals(Status.BLOCKED_BY_TEST, sink.last())
        assertEquals(1, port.count("connect"))
        s.stop()
    }

    @Test fun withTheGateBusyItReportsBusyAndRetriesEvery5Seconds() {
        val (s, port, p) = setup { gateFree = false }
        val (sch, sink) = p
        assertEquals(Status.BUSY, sink.last())
        sch.advanceSeconds(4.9)
        assertEquals(0, port.count("connect"))
        port.gateFree = true
        sch.advanceSeconds(0.2)
        assertEquals(1, port.count("connect"))
        s.stop()
        assertEquals(1, port.gateReleased)
    }

    @Test fun noPermissionAndBluetoothOffAreReported() {
        setup { permission = false }.let { (s, port, p) ->
            assertEquals(Status.PERMISSION_NEEDED, p.second.last()); assertEquals(0, port.count("connect")); s.stop()
        }
        setup { bt = false }.let { (s, port, p) ->
            assertEquals(Status.OFF, p.second.last()); assertEquals(0, port.count("connect")); s.stop()
        }
    }
}
