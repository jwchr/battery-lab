package com.batterylab.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T18: the result card math. Synthetic values only. */
class ResultMathTest {
    private fun ok(sec: Double, dbm: Int) = Sample((sec * SEC).toLong(), dbm, true)
    private fun fail(sec: Double) = Sample((sec * SEC).toLong(), null, false)

    @Test fun nearestRankMatchesKnownInputs() {
        val ten = (1..10).toList()
        assertEquals(5, ResultMath.nearestRank(ten, 50))
        assertEquals(1, ResultMath.nearestRank(ten, 10))
        assertEquals(9, ResultMath.nearestRank(ten, 90))
        val five = listOf(10, 20, 30, 40, 50)
        assertEquals(30, ResultMath.nearestRank(five, 50)) // ceil(2.5) = 3
        assertEquals(10, ResultMath.nearestRank(five, 10)) // ceil(0.5) = 1
        assertEquals(50, ResultMath.nearestRank(five, 90)) // ceil(4.5) = 5
        assertEquals(20, ResultMath.nearestRank(listOf(10, 20, 30, 40), 50)) // ceil(2) = 2
        assertEquals(7, ResultMath.nearestRank(listOf(7), 10))
        assertNull(ResultMath.nearestRank(emptyList<Int>(), 50))
    }

    @Test fun windowsAreCutAtMarkersAndAtTheSessionEnd() {
        val w = ResultMath.windows(listOf(Marker(40 * SEC, "moving"), Marker(10 * SEC, "near"), Marker(70 * SEC, "far")),
            0, 90 * SEC)
        assertEquals(listOf(Window("near", 10 * SEC, 40 * SEC), Window("moving", 40 * SEC, 70 * SEC), Window("far", 70 * SEC, 90 * SEC)), w)
        assertTrue(ResultMath.windows(emptyList(), 0, 90 * SEC).isEmpty())
    }

    @Test fun anEmptyWindowReportsNoResult() {
        assertNull(ResultMath.stats(listOf(ok(1.0, -60)), Window("far", 5 * SEC, 9 * SEC)))
        assertNull(ResultMath.stats(emptyList(), Window("far", 0, 9 * SEC)))
    }

    @Test fun failedReadsCountInTheShareButStayOutOfTheMedians() {
        val s = listOf(ok(0.1, -50), fail(0.2), ok(0.3, -60), fail(0.4), ok(0.5, -70))
        val st = ResultMath.stats(s, Window("", 0, 2 * SEC))!!
        assertEquals(5, st.rows)
        assertEquals(3, st.okRows)
        assertEquals(0.6, st.successShare, 1e-9)
        assertEquals(-60, st.medianDbm)
        assertEquals(-70, st.p10Dbm)
        assertEquals(-50, st.p90Dbm)
        assertEquals(2.5, st.meanRowsPerSecond, 1e-9)
    }

    @Test fun rowsPerBucketAreCountedFromTBootNsAndALastPartialSecondIsLeftOut() {
        val start = 10.5
        val s = listOf(ok(10.5, -60), ok(10.9, -60), ok(11.6, -60), ok(12.4, -60), ok(13.0, -60), ok(13.1, -60))
        val st = ResultMath.stats(s, Window("near", (start * SEC).toLong(), (13.2 * SEC).toLong()))!!
        // Buckets [10.5, 11.5) and [11.5, 12.5); 13.0 and 13.1 fall in the partial third second.
        assertEquals(2, st.buckets)
        assertEquals(2, st.medianRowsPerBucket)
        assertEquals(6, st.rows)
        val sparse = ResultMath.stats(listOf(ok(0.2, -60)), Window("far", 0, 3 * SEC))!!
        assertEquals(0, sparse.medianRowsPerBucket) // buckets 1, 0, 0
    }

    private fun window(label: String, okCount: Int, failCount: Int, dbm: Int, perSecond: Int = 1, seconds: Int = 10): WindowStats {
        val samples = mutableListOf<Sample>()
        var n = 0
        for (sec in 0 until seconds) for (k in 0 until perSecond) {
            val t = sec + k / (perSecond + 1.0)
            samples += if (n++ < okCount) ok(t, dbm) else if (n <= okCount + failCount) fail(t) else continue
        }
        return ResultMath.stats(samples, Window(label, 0, seconds * SEC))!!
    }

    @Test fun linkG0FlipsExactlyAt90PercentAndAt10Db() {
        val near = window("near", 10, 0, -50)
        val far10 = window("far", 10, 0, -60)
        val far9 = window("far", 10, 0, -59)
        val whole90 = window("", 90, 10, -55, perSecond = 10)
        val whole89 = window("", 89, 11, -55, perSecond = 10)
        assertEquals(100, whole90.rows)
        assertTrue(ResultMath.g0Link(whole90, near, far10).pass)
        assertFalse(ResultMath.g0Link(whole89, near, far10).pass)
        assertFalse(ResultMath.g0Link(whole90, near, far9).pass)
        assertFalse(ResultMath.g0Link(whole90, near, null).pass)
        assertTrue(ResultMath.g0Link(whole90, near, far10).explanation.contains("difference 10 dB"))
    }

    @Test fun broadcastG0FlipsExactlyAtAMedianOfOneRowPerBucketAndAt10Db() {
        val near = window("near", 10, 0, -50)
        val far = window("far", 10, 0, -60)
        assertEquals(1, near.medianRowsPerBucket)
        assertTrue(ResultMath.g0Broadcast(near, far).pass)
        // Five rows in ten seconds: the median bucket holds none.
        val thinFar = ResultMath.stats((0 until 5).map { ok(it * 2.0, -60) }, Window("far", 0, 10 * SEC))!!
        assertEquals(0, thinFar.medianRowsPerBucket)
        assertFalse(ResultMath.g0Broadcast(near, thinFar).pass)
        // Six rows in ten seconds at one per bucket: the median bucket holds one.
        val justFar = ResultMath.stats((0 until 6).map { ok(it.toDouble(), -60) }, Window("far", 0, 10 * SEC))!!
        assertEquals(1, justFar.medianRowsPerBucket)
        assertTrue(ResultMath.g0Broadcast(near, justFar).pass)
        assertFalse(ResultMath.g0Broadcast(near, window("far", 10, 0, -59)).pass)
    }

    @Test fun theCardIsComputedFromRawRowsWithG0OnlyForRingSignals() {
        val dir = SessionStore(newRoot()).dir("1700000000000").apply { mkdirs() }
        File(dir, FileNames.PROPERTIES).writeText("format=1\nmin_reader=1\nstart_boot_ns=0\nend_boot_ns=${30 * SEC}\n")
        File(dir, FileNames.STREAMS).writeText("id,kind\nring,ring_link\nwifi,wifi_link\n")
        File(dir, FileNames.EVENTS).writeText("seq,t_boot_ns,t_wall_ms,type,stream,value,text\n" +
            "1,${5 * SEC},0,marker,,,\"near\"\n2,${20 * SEC},0,marker,,,\"far\"\n")
        val header = "seq,t_boot_ns,t_wall_ms,emitter,rssi_dbm,status\n"
        val ring = (0 until 30).joinToString("") { s -> "${s + 1},${s * SEC + 1},0,0,${if (s < 20) -50 else -65},ok\n" }
        File(dir, "obs_ring.csv").writeText(header + ring)
        File(dir, "obs_wifi.csv").writeText(header + "1,${SEC},0,0,,not_connected\n")
        val r = ResultMath.compute(dir)
        val ringResult = r.streams.first { it.stream.id == "ring" }
        assertEquals(30, ringResult.whole!!.rows)
        assertEquals(listOf("near", "far"), ringResult.windows.map { it.first.label })
        assertEquals(15, ringResult.windows[0].second!!.rows)
        assertTrue(ringResult.g0!!.pass)
        val wifi = r.streams.first { it.stream.id == "wifi" }
        assertNull(wifi.g0)
        assertEquals(0, wifi.whole!!.okRows)
        assertNull(wifi.whole!!.medianDbm)
        assertNull(wifi.windows[0].second)
    }
}
