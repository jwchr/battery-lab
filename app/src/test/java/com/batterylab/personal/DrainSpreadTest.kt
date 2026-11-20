package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrainSpreadTest {
    private val H = 3_600_000L
    /** A stretch starting at [startH] hours that drops [drop] points over [hours] hours. */
    private fun s(startH: Long, hours: Long, drop: Int) = DischargeStretch(startH * H, (startH + hours) * H, 100, 100 - drop)

    @Test fun nearestRankPercentilesOnKnownRates() {
        // Rates 1..10 % per hour, one stretch each.
        val stretches = (1..10).map { s(it * 24L, 2, it * 2) }
        val d = DrainSpreadMath.of(stretches, 0)!!
        assertEquals(10, d.stretches)
        assertEquals(5.0, d.median, 1e-9)   // ceil(50 * 10 / 100) = 5th
        assertEquals(1.0, d.p10, 1e-9)      // 1st
        assertEquals(9.0, d.p90, 1e-9)      // 9th
        assertEquals(1.0, d.calmest.percentPerHour, 1e-9)
        assertEquals(10.0, d.hungriest.percentPerHour, 1e-9)
    }

    @Test fun ratesFallIntoBandsWithLowerEdgesInclusive() {
        // 0.5, 1.0, 2.0, 3.0, 4.0, 8.0, 12.0 % per hour.
        val stretches = listOf(s(0, 8, 4), s(10, 4, 4), s(20, 2, 4), s(30, 2, 6), s(40, 1, 4), s(50, 1, 8), s(60, 1, 12))
        assertEquals(listOf(1, 1, 2, 1, 2), DrainSpreadMath.of(stretches, 0)!!.bands)
    }

    @Test fun onlyStretchesEndingInTheWindowCount() {
        val old = (0 until 5).map { s(it * 10L, 2, 4) }        // all end by hour 42
        val recent = (0 until 5).map { s(100 + it * 10L, 2, 8) }
        val d = DrainSpreadMath.of(old + recent, 100 * H)!!
        assertEquals(5, d.stretches)
        assertEquals(4.0, d.median, 1e-9)
    }

    @Test fun fewerThanFiveStretchesGivesNoResult() {
        assertNull(DrainSpreadMath.of((0 until 4).map { s(it * 10L, 2, 4) }, 0))
    }

    @Test fun rankOfASingleValueIsThatValue() {
        assertEquals(3.0, DrainSpreadMath.rank(listOf(3.0), 10), 1e-9)
        assertEquals(3.0, DrainSpreadMath.rank(listOf(3.0), 90), 1e-9)
    }
}
