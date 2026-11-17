package com.batterylab.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 14+ can deliver plug events late; one that disagrees with the charger now is skipped. */
class PlugEventTest {
    @Test fun matchingStateIsCurrent() {
        assertTrue(PlugEvent.stillCurrent(connected = true, pluggedNow = true))
        assertTrue(PlugEvent.stillCurrent(connected = false, pluggedNow = false))
    }

    @Test fun oppositeStateIsLate() {
        assertFalse(PlugEvent.stillCurrent(connected = true, pluggedNow = false))
        assertFalse(PlugEvent.stillCurrent(connected = false, pluggedNow = true))
    }

    @Test fun unknownStateKeepsTheEvent() {
        assertTrue(PlugEvent.stillCurrent(connected = true, pluggedNow = null))
    }
}
