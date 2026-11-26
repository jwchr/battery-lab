package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #59: the default rate is EIA's U.S. 12-month residential average, rounded to the nearest cent. */
class DefaultPriceSourceTest {
    @Test
    fun defaultMatchesEiaRollingAverage() {
        val eiaRolling12MonthsEndingSep2026 = 18.05 // Electric Power Monthly, Table 5.3, residential
        assertEquals(Math.round(eiaRolling12MonthsEndingSep2026).toDouble(), StatsActivity.DEFAULT_CENTS_PER_KWH, 0.0)
    }

    @Test
    fun glossaryNamesTheSource() {
        val how = Glossary.entries.single { it.term == "Electricity so far" }.howWorkedOut
        assertTrue(how.contains("18 cents per kWh"))
        assertTrue(how.contains("U.S. Energy Information Administration"))
        assertTrue(how.contains("Table 5.3"))
    }
}
