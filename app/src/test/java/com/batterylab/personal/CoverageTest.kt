package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTest {
    private val M = 60_000L
    private val step = 60 * M
    private fun every15(fromMin: Long, toMin: Long) = (fromMin..toMin step 15).map { it * M }

    @Test fun steadyReadingsCoverTheWholeWindow() {
        val c = CoverageMath.of(every15(0, 24 * 60), 0, 24 * 60 * M, step)!!
        assertEquals(100.0, c.percent, 1e-9)
        assertEquals(0, c.gaps)
        assertEquals(97, c.readings)
        assertFalse(c.startsAtFirstReading)
    }

    @Test fun aStepLongerThanTheLimitIsAGap() {
        // Readings stop from 6:00 to 9:00, so that 3 hours counts against coverage.
        val times = every15(0, 6 * 60) + every15(9 * 60, 12 * 60)
        val c = CoverageMath.of(times, 0, 12 * 60 * M, step)!!
        assertEquals(1, c.gaps)
        assertEquals(180 * M, c.gapMs)
        assertEquals(180 * M, c.longestGapMs)
        assertEquals(75.0, c.percent, 1e-9)
    }

    @Test fun aStepAtExactlyTheLimitIsNotAGap() {
        val c = CoverageMath.of(listOf(0L, 60 * M, 120 * M), 0, 120 * M, step)!!
        assertEquals(0, c.gaps)
    }

    @Test fun timeSinceTheLastReadingCountsAsAGap() {
        val c = CoverageMath.of(every15(0, 10 * 60), 0, 12 * 60 * M, step)!!
        assertEquals(1, c.gaps)
        assertEquals(120 * M, c.gapMs)
    }

    @Test fun aGapCrossingTheWindowStartCountsOnlyItsInsidePart() {
        // Last reading before the window at -2:00, next at +1:00: only the hour inside counts.
        val times = listOf(-120 * M) + every15(60, 4 * 60)
        val c = CoverageMath.of(times, 0, 4 * 60 * M, step)!!
        assertEquals(60 * M, c.gapMs)
        assertEquals(75.0, c.percent, 1e-9)
    }

    @Test fun shortHistoryStartsTheWindowAtTheFirstReading() {
        val c = CoverageMath.of(every15(20 * 60, 24 * 60), 0, 24 * 60 * M, step)!!
        assertTrue(c.startsAtFirstReading)
        assertEquals(20 * 60 * M, c.fromMs)
        assertEquals(100.0, c.percent, 1e-9)
    }

    @Test fun readingsAfterTheWindowEndAreIgnoredAndOrderDoesNotMatter() {
        val times = (every15(0, 4 * 60) + every15(5 * 60, 6 * 60)).shuffled(java.util.Random(7))
        val c = CoverageMath.of(times, 0, 4 * 60 * M, step)!!
        assertEquals(17, c.readings)
        assertEquals(0, c.gaps)
    }

    @Test fun noReadingsOrTooShortASpanGivesNoResult() {
        assertNull(CoverageMath.of(emptyList(), 0, 24 * 60 * M, step))
        assertNull(CoverageMath.of(listOf(25 * 60 * M), 0, 24 * 60 * M, step))
        assertNull(CoverageMath.of(listOf(23 * 60 * M + 30 * M), 0, 24 * 60 * M, step))
    }
}
