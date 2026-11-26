package com.batterylab.personal.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RadioCapabilitiesTest {
    @Test fun android15AnswersNoWithoutAsking() {
        var asked = 0
        val rows = RadioCapabilities.ranging(35, { asked++; true }, { asked++; true })
        assertEquals(0, asked)
        assertEquals(listOf(false, false), rows.map { it.second })
    }

    @Test fun android16AsksBothChecksAndReportsTheirAnswers() {
        var asked = 0
        val rows = RadioCapabilities.ranging(36, { asked++; true }, { asked++; null })
        assertEquals(2, asked)
        assertEquals(listOf(true, null), rows.map { it.second })
    }

    @Test fun android17StillAsks() {
        val rows = RadioCapabilities.ranging(37, { false }, { true })
        assertEquals(listOf(false, true), rows.map { it.second })
    }

    @Test fun rowsAreLabeledForTheCard() {
        val labels = RadioCapabilities.ranging(36, { true }, { true }).map { it.first }
        assertEquals(2, labels.size)
        assertFalse(labels.any { it.isBlank() })
        assertEquals(36, RadioCapabilities.RANGING_SDK)
    }
}
