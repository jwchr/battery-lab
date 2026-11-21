package com.batterylab.personal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ClockBoundaryTest {
    private val zone = ZoneId.of("America/New_York")
    private val hourMs = 3_600_000L
    private val epsilon = 1e-9

    private fun local(time: String) = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli()
    private fun dayStart(day: String) = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun zoned(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
    private val weekOf: (Long) -> Long = { ms ->
        zoned(ms).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }
    private val isWeekend: (Long) -> Boolean = { ms ->
        zoned(ms).dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }

    private fun stretch(from: String, to: String) = DischargeStretch(local(from), local(to), 90, 78)

    private fun assertSingleStretch(from: String, to: String, expectedHours: Int): DischargeStretch {
        val start = local(from)
        val end = local(to)
        // Sample in real hours, including both occurrences of a repeated fall-back hour.
        val points = (0..expectedHours).map { h -> BatteryPoint(start + h * hourMs, 90 - h * 2, false) }
        assertEquals(end, points.last().timeMs)
        val stretches = BatteryMath.dischargeStretches(points)
        assertEquals(1, stretches.size)
        return stretches.single().also {
            assertEquals(start, it.startMs)
            assertEquals(end, it.endMs)
            assertEquals(expectedHours.toDouble(), it.hours, epsilon)
            assertEquals(2.0, it.percentPerHour, epsilon)
        }
    }

    @Test fun localMidnightDoesNotSplitADischarge() {
        assertSingleStretch("2026-02-10T23:00", "2026-02-11T03:00", 4)
    }

    @Test fun springForwardStretchUsesSixRealHoursDespiteSevenClockHours() {
        assertSingleStretch("2026-03-07T23:00", "2026-03-08T06:00", 6)
    }

    @Test fun fallBackStretchUsesSixRealHoursDespiteFiveClockHours() {
        assertSingleStretch("2026-10-31T23:00", "2026-11-01T04:00", 6)
    }

    @Test fun dstNightStretchesBelongToTheirLocalMondayWeek() {
        val spring = stretch("2026-03-07T23:00", "2026-03-08T06:00")
        val fall = stretch("2026-10-31T23:00", "2026-11-01T04:00")
        val weeks = BatteryMath.weeklyRuntime(listOf(spring, fall), weekOf)
        assertEquals(listOf(dayStart("2026-03-02"), dayStart("2026-10-26")), weeks.map { it.weekStartMs })
        assertEquals(listOf(1, 1), weeks.map { it.stretches })
        weeks.forEach {
            assertEquals(6.0, it.observedHours, epsilon)
            assertEquals(50.0, it.fullChargeHours, epsilon)
        }
    }

    private fun assertDstWeekSplit(day: String, monday: String, priorMonday: String, sundayHours: Int) {
        // A continuous stretch from DST Sunday midnight into Monday, split at local midnight.
        val s = stretch("${day}T00:00", "${monday}T02:00")
        val weeks = BatteryMath.weeklyRuntime(listOf(s), weekOf)
        assertEquals(listOf(dayStart(priorMonday), dayStart(monday)), weeks.map { it.weekStartMs })
        assertEquals(listOf(1, 1), weeks.map { it.stretches })
        assertEquals(sundayHours.toDouble(), weeks[0].observedHours, epsilon)
        assertEquals(2.0, weeks[1].observedHours, epsilon)
        assertEquals(s.hours, weeks.sumOf { it.observedHours }, epsilon)
        weeks.forEach { assertEquals(s.fullChargeHours, it.fullChargeHours, epsilon) }
    }

    @Test fun springWeekEndsAfter167RealHours() {
        assertDstWeekSplit("2026-03-08", "2026-03-09", "2026-03-02", 23)
    }

    @Test fun fallWeekEndsAfter169RealHours() {
        assertDstWeekSplit("2026-11-01", "2026-11-02", "2026-10-26", 25)
    }

    @Test fun springWeekendGroupingUsesTheEpochMidpointOnLocalMonday() {
        // The real midpoint is Monday 00:15; averaging wall times would give Sunday 23:45.
        val crossing = stretch("2026-03-08T00:00", "2026-03-09T23:30")
        val split = WeekSplitMath.of(listOf(crossing,
            stretch("2026-03-10T10:00", "2026-03-10T16:00"),
            stretch("2026-03-11T10:00", "2026-03-11T16:00")), dayStart("2026-03-08"), isWeekend)!!
        assertNull(split.weekends)
        assertEquals(3, split.weekdays!!.stretches)
        assertEquals(58.5, split.weekdays.hours, epsilon)
        assertEquals(36, split.weekdays.drop)
    }

    @Test fun fallWeekendGroupingUsesTheEpochMidpointOnLocalSunday() {
        // The real midpoint is Sunday 23:45 (Monday in UTC); wall times give Monday 00:15.
        val crossing = stretch("2026-11-01T00:00", "2026-11-03T00:30")
        val split = WeekSplitMath.of(listOf(crossing,
            stretch("2026-11-07T10:00", "2026-11-07T16:00"),
            stretch("2026-11-08T10:00", "2026-11-08T16:00")), dayStart("2026-11-01"), isWeekend)!!
        assertNull(split.weekdays)
        assertEquals(3, split.weekends!!.stretches)
        assertEquals(61.5, split.weekends.hours, epsilon)
        assertEquals(36, split.weekends.drop)
    }

    private fun stats(times: List<Long>, percents: List<Int>) = BatteryStats.device(
        times.zip(percents) { time, percent -> Reading("phone", time, percent, false, "android") },
        hourOf = { zoned(it).hour },
        utcOffsetMs = { zoned(it).offset.totalSeconds * 1000L }
    )

    @Test fun springHourGroupingSkipsTheNonexistentHour() {
        val start = local("2026-03-08T00:00")
        val s = stats((0..3).map { start + it * hourMs }, listOf(90, 88, 84, 78))
        assertEquals(3.0, s.hoursOnBattery, epsilon)
        assertEquals(3.0, s.longestOnBatteryHours, epsilon)
        assertEquals(2.0, s.drainByHour[0]!!, epsilon)
        assertEquals(4.0, s.drainByHour[1]!!, epsilon)
        assertNull(s.drainByHour[2])
        assertEquals(6.0, s.drainByHour[3]!!, epsilon)
        assertEquals(listOf(0, 1, 3), s.drainByHour.indices.filter { s.drainByHour[it] != null })
    }

    @Test fun fallHourGroupingCombinesBothOccurrencesOfOneAmByRealTime() {
        val start = local("2026-11-01T00:00")
        val s = stats((0..4).map { start + it * hourMs }, listOf(90, 88, 84, 76, 70))
        assertEquals(4.0, s.hoursOnBattery, epsilon)
        assertEquals(4.0, s.longestOnBatteryHours, epsilon)
        assertEquals(2.0, s.drainByHour[0]!!, epsilon)
        assertEquals(6.0, s.drainByHour[1]!!, epsilon) // (4 + 8) points / two real hours
        assertEquals(6.0, s.drainByHour[2]!!, epsilon)
        assertEquals(listOf(0, 1, 2), s.drainByHour.indices.filter { s.drainByHour[it] != null })
    }

    private fun assertDstDayCoverage(day: String, nextDay: String, expectedHours: Int) {
        // Coverage has no calendar grouping; supply the actual local day's epoch window.
        val start = dayStart(day)
        val end = dayStart(nextDay)
        val times = (-1..expectedHours + 1).map { start + it * hourMs }
        val c = CoverageMath.of(times, start, end, hourMs)!!
        assertEquals(start, c.fromMs)
        assertEquals(end, c.toMs)
        assertEquals(expectedHours * hourMs, c.spanMs)
        assertEquals(expectedHours + 1, c.readings) // Both window endpoints are inclusive.
        assertEquals(0, c.gaps)
        assertEquals(0L, c.gapMs)
        assertEquals(100.0, c.percent, epsilon)
        assertFalse(c.startsAtFirstReading)
    }

    @Test fun springLocalDayCoverageUses23Hours() {
        assertDstDayCoverage("2026-03-08", "2026-03-09", 23)
    }

    @Test fun fallLocalDayCoverageUses25Hours() {
        assertDstDayCoverage("2026-11-01", "2026-11-02", 25)
    }
}
