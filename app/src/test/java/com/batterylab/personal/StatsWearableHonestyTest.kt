package com.batterylab.personal

import org.junit.Assert.*
import org.junit.Test

class StatsWearableHonestyTest {
    private val hour = 3_600_000L
    private val gaps = GapLimits()
    private fun reading(h: Int, percent: Int, charging: Boolean = false) =
        Reading("watch", h * hour, percent, charging, "test")
    private fun history(from: Int, to: Int) = (from..to).map { reading(it, 100 - (it - from)) }

    @Test fun dailyComparisonUsesTheSameDaysAndExcludesEarlierAndLaterUse() {
        val result = StatsComparison.matched(listOf(history(0, 96), history(24, 72)), gaps)!!
        assertEquals(24 * hour, result.fromMs)
        assertEquals(72 * hour, result.toMs)
        assertEquals(2, result.daily.size)
        result.daily.forEach { assertEquals(24.0, it.percentPerDay, 1e-9) }
        result.runtime.forEach { assertEquals(100.0, it.fullChargeHours, 1e-9) }
    }

    @Test fun physicalFieldsCannotChangePercentBasedComparisons() {
        val plain = history(0, 48)
        val withPhysicalFields = plain.mapIndexed { i, r ->
            r.copy(tempTenthsC = i * 10, voltageMv = 3000 + i,
                remainingUah = 5_000_000 - i * 100_000,
                detail = ReadingDetail(health = 2, cycleCount = i, energyRaw = i.toLong()))
        }
        assertEquals(StatsComparison.matched(listOf(plain, plain), gaps),
            StatsComparison.matched(listOf(plain, withPhysicalFields), gaps))
    }

    @Test fun needsTwoSharedDaysRatherThanTwoDaysForEachSeparately() {
        val result = StatsComparison.matched(listOf(history(0, 48), history(24, 72)), gaps)!!
        assertTrue(result.daily.isEmpty())
        assertEquals(2, result.runtime.size)
        assertEquals(2, StatsComparison.matched(listOf(history(0, 48), history(0, 48)), gaps)!!.daily.size)
    }

    @Test fun disjointOrJustTouchingHistoriesCannotBeRanked() {
        assertNull(StatsComparison.matched(listOf(history(0, 10), history(11, 20)), gaps))
        assertNull(StatsComparison.matched(listOf(history(0, 10), history(10, 20)), gaps))
    }

    @Test fun unobservedDevicesDoNotBlockTheOtherTwoOrChangeTheirIndices() {
        val result = StatsComparison.matched(listOf(history(0, 48), emptyList(), history(0, 48),
            listOf(reading(10, 50))), gaps)!!
        assertEquals(listOf(0, 2), result.daily.map { it.deviceIndex })
        assertEquals(listOf(0, 2), result.runtime.map { it.deviceIndex })
        assertNull(StatsComparison.matched(listOf(emptyList(), history(0, 48)), gaps))
    }

    @Test fun invalidLevelsCannotExpandTheSharedPeriod() {
        val result = StatsComparison.matched(listOf(history(0, 72),
            listOf(reading(0, -1)) + history(24, 72)), gaps)!!
        assertEquals(24 * hour, result.fromMs)
        result.daily.forEach { assertEquals(24.0, it.percentPerDay, 1e-9) }
    }

    @Test fun boundariesDoNotInventPercentDropsOrFullChargeRuns() {
        val sparse = listOf(reading(0, 100), reading(2, 90), reading(48, 80), reading(50, 70))
        val result = StatsComparison.matched(listOf(sparse, history(1, 49)), gaps)!!
        // Only the two real interior readings count, over the same two calendar days.
        assertEquals(5.0, result.daily.first().percentPerDay, 1e-9)
        // The interior gap is too long to support a runtime projection.
        assertEquals(listOf(1), result.runtime.map { it.deviceIndex })
    }

    @Test fun runtimeExcludesUseOutsideTheOverlapAndTimeOnTheCharger() {
        val readings = listOf(reading(0, 100), reading(1, 20), reading(2, 40, true),
            reading(3, 80, true), reading(4, 80), reading(6, 70), reading(8, 60))
        val other = listOf(reading(2, 100), reading(4, 90), reading(6, 80), reading(8, 70))
        val result = StatsComparison.matched(listOf(readings, other), gaps)!!
        assertEquals(20.0, result.runtime.first().fullChargeHours, 1e-9)
        assertEquals(20.0, result.runtime.last().fullChargeHours, 1e-9)
    }

    @Test fun chargesBeforeAndAfterTheSharedPeriodDoNotAffectWeeklyPace() {
        val readings = history(0, 72).map { r ->
            when ((r.timeMs / hour).toInt()) {
                0, 60 -> r.copy(percent = 20, charging = true)
                1, 61 -> r.copy(percent = 90, charging = true)
                30 -> r.copy(percent = 20, charging = true)
                31 -> r.copy(percent = 80, charging = true)
                else -> r
            }
        }
        val row = StatsComparison.matched(listOf(readings, history(12, 60)), gaps)!!.daily.first()
        assertEquals(3.5, row.chargesPerWeek, 1e-9)
        assertNull(row.hoursBetweenCharges)
    }

    @Test fun sortingForComparisonDoesNotRewriteHistory() {
        val ordered = history(0, 48)
        val reversed = ordered.reversed()
        assertEquals(StatsComparison.matched(listOf(ordered, ordered), gaps),
            StatsComparison.matched(listOf(reversed, ordered), gaps))
        assertEquals(48 * hour, reversed.first().timeMs)
    }

    private fun capability(kind: String, field: String, status: String = CapabilityProbe.VERIFIED) =
        CapabilityRow(field, kind, "test", status, "test", "test", "123", "2", null, 0L)

    @Test fun oldWearableInventoriesCannotAdvertisePhysicalBatteryMeasurements() {
        val fields = listOf("temperature", "voltage", "remaining_uah", "health", "chemistry",
            "cycle_count", "energy_nwh", "current_now", "capacity_mah", "power_watts")
        for (kind in listOf("watch", "ring")) {
            val original = fields.map { capability(kind, it, CapabilityProbe.DISABLED) }
            val displayed = StatsCapabilities.batteryOnly(original)
            assertEquals(fields, displayed.map { it.fieldKey })
            displayed.forEach {
                assertEquals(CapabilityProbe.UNSUPPORTED, it.status)
                assertNull(it.observedMinStep)
                assertNull(it.lastValueSample)
                assertEquals("—", it.units)
            }
            assertTrue(original.all { it.status == CapabilityProbe.DISABLED && it.lastValueSample != null })
        }
    }

    @Test fun percentAndChargingCapabilitiesKeepTheirEvidenceAndUnknownAge() {
        val rows = listOf(capability("watch", "percent_widget", CapabilityProbe.UNKNOWN_AGE),
            capability("watch", "percent_wear_os"), capability("ring", "percent_ble"),
            capability("ring", "charging"))
        assertEquals(rows, StatsCapabilities.batteryOnly(rows))
    }

    @Test fun phoneCapabilitiesAreUnchanged() {
        val rows = listOf("temperature", "voltage", "health", "cycle_count", "remaining_uah", "energy_nwh")
            .map { capability("phone", it) }
        assertEquals(rows, StatsCapabilities.batteryOnly(rows))
    }

    @Test fun radioMeasurementsCannotLeakIntoBatteryStatsOrTheirSharedList() {
        val rows = listOf(capability("ring", "rssi"), capability("ring", "percent_ble"))
        assertEquals(listOf(rows.last()), StatsCapabilities.batteryOnly(rows))
    }
}
