package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {
    @Test fun sectionsMatchStats() {
        assertEquals(StatsActivity.SECTION_ICONS.keys, Glossary.entries.map { it.section }.toSet())
    }

    @Test fun fieldsArePresentAndShort() {
        Glossary.entries.forEach { entry ->
            assertTrue(entry.toString(), listOf(entry.section, entry.term, entry.meaning, entry.howWorkedOut).all { it.isNotBlank() })
            assertTrue(entry.term, entry.meaning.length <= 300)
            assertTrue(entry.term, entry.howWorkedOut.length <= 600)
        }
    }

    @Test fun noEmDashes() {
        Glossary.entries.forEach { entry ->
            assertTrue(entry.term, listOf(entry.section, entry.term, entry.meaning, entry.howWorkedOut).none { '\u2014' in it })
        }
    }

    @Test fun termsAreUniqueWithinEachSection() {
        val keys = Glossary.entries.map { it.section to it.term }
        assertEquals(keys.size, keys.toSet().size)
    }
}
