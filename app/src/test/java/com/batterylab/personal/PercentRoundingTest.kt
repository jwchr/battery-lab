package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Neon deep review, Nov 26, 2026: float math saved 29, 57 and 58 as one less. */
class PercentRoundingTest {
    @Test fun everyLevelOutOf100IsSavedAsIs() {
        for (level in 0..100) assertEquals("level $level", level, PhoneBattery.percentOf(level, 100))
    }

    @Test fun otherScalesFloorAndClamp() {
        assertEquals(50, PhoneBattery.percentOf(500, 1000))
        assertEquals(29, PhoneBattery.percentOf(299, 1000))
        assertEquals(100, PhoneBattery.percentOf(120, 100))
        assertEquals(0, PhoneBattery.percentOf(0, 255))
    }

    @Test fun extremeLevelsClampWithoutOverflow() {
        assertEquals(100, PhoneBattery.percentOf(Int.MAX_VALUE, 1))
        assertEquals(0, PhoneBattery.percentOf(Int.MIN_VALUE, 1))
    }
}
