package com.batterylab.personal

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class StatsPrecomputerTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val start = 1_700_000_000_000L
    private val now = start + 28 * day
    // Stretch limit set below the 8-hour default so the test fails if the precompute ignores the gap limits.
    private val gaps = GapLimits(2 * hour, 4 * hour)
    private fun <T> ok(value: T) = SectionResult.Ok(value)
    private fun <T> SectionResult<T>.value(): T {
        assertTrue("Expected Ok, got $this", this is SectionResult.Ok)
        return (this as SectionResult.Ok).value
    }
    private fun hourOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
    private fun weekend(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)
        .let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }

    private fun raw(): StatsPrecomputer.Raw {
        val phone = (0 until 56).flatMap { cycle ->
            val base = start + cycle * 12 * hour
            val charging = listOf(20, 50, 80, 100).mapIndexed { i, pct ->
                Reading("phone", base + i * hour / 2, pct, true, "android", 300 + i * 10, 3900, pct * 50_000,
                    ReadingDetail(plugged = if (cycle % 2 == 0) 1 else 2, displayState = 1, transport = "wifi", cycleCount = cycle + 1))
            }
            val drain = listOf(96, 76, 74, 72, 70, 68, 66).mapIndexed { i, pct ->
                Reading("phone", base + 2 * hour + i * 3 * hour / 2, pct, false, "android", 290, 3800, pct * 50_000,
                    ReadingDetail(displayState = 1, transport = "wifi", cycleCount = cycle + 1))
            }.filterIndexed { i, _ -> (cycle != 4 || i != 4) && (cycle != 6 || i !in 3..4) } // cycle 6: a 4.5-hour gap, over the stretch limit
            charging + drain
        }
        val watch = phone.map { it.copy(device = "watch:sample", source = "samsung_widget", remainingUah = null) }
        val companion = watch.map { it.copy(source = "wear_os", percent = (it.percent - 1).coerceAtLeast(0)) }.reversed()
        val ring = watch.filter { it.timeMs < now - 7 * hour }.map { it.copy(device = "ring:sample") }
        val screen = (0 until 56).map { cycle -> Span(start + cycle * 12 * hour + 2 * hour, start + cycle * 12 * hour + 7 * hour / 2) }
        val apps = (0 until 12).associate { i -> "sample.app$i" to screen.map {
            Span(it.startMs + i * (it.endMs - it.startMs) / 12, it.startMs + (i + 1) * (it.endMs - it.startMs) / 12)
        } }
        val entry = JournalEntry(1, start + 14 * day, null, "phone", "charger", "Sample change", null)
        val entries = listOf(entry) + (2L..14L).map { entry.copy(id = it, category = if (it == 2L) "note" else "charger",
            device = if (it == 3L) "watch" else "phone") }
        val forecast = Forecast(1, "phone", start, 90, 2.0, 45.0, 40.0, false, "sample-model", 85.0, 80.0, 70.0, "SCORED")
        val capability = CapabilityRow("percent", "phone", "sample-build", CapabilityProbe.VERIFIED, "sample-api", "%", null, "1", null, now)
        return StatsPrecomputer.Raw(listOf(Triple("phone", "Phone", 1), Triple("watch:sample", "Watch", 2), Triple("ring:sample", "Ring", 3)),
            listOf(phone, watch, ring), gaps, ok(UsageWindow(screen, apps, start)),
            ok(phone.map { it.copy(source = "samsung_widget") }), ok(companion),
            ok(listOf(forecast, forecast.copy(id = 2, status = "PENDING", actual2h = null, actual4h = null, actual8h = null))),
            ok(entries), ok(listOf(now to "poll ms=25 cpu=5 db=1000")), ok(listOf(capability)), ok(2),
            18.0, true, { "Label $it" })
    }

    @Test fun everySectionMatchesTheDirectAnalysis() {
        val raw = raw()
        val r = StatsPrecomputer.compute(raw, now)
        val data = raw.data.map { BatteryStats.fullHoldAsCharging(it) }
        val phone = data[0]
        val usage = raw.usage.value()!!
        val capacity = HonestyAuditor.estimatePhoneCapacity(phone, gaps)
        val stretches = data.map { BatteryMath.dischargeStretches(it.map(BatteryPoint::of), gaps = gaps) }
        val charges = data.map { ChargeCurves.observedCharges(it, gaps) }
        val idle = IdleAnalysis.stretches(phone, usage.screenOn, gaps = gaps, screenCoverageStartMs = usage.coverageStartMs)
        assertEquals(now, r.now)
        assertEquals(data.sumOf { it.size }, r.readingCount)
        assertEquals(capacity, r.health.value().capacity)
        assertEquals(CapacityProxy.summarize(phone, now), r.health.value().phoneCapacity)
        assertEquals(data.map { BatteryStats.device(it, ::hourOf, gaps) }, r.stats.value())
        val watch = (data[1] + raw.watchCompanion.value()).sortedBy { it.timeMs }
        assertEquals(watch.map { it.timeMs }, r.audit.value().watchTimes)
        assertEquals(HonestyAuditor.audit(phone, raw.phoneWidget.value(), watch, data[2], r.health.value().headlineMah, gaps), r.audit.value().report)
        assertTrue(r.audit.value().report.watchWidgetVsCompanionPairs > 0)
        val times = data.map { ChargeTimes.analyze(it, gaps) }
        assertEquals(times, r.chargeTimes.value().times)
        assertEquals(times.filterNotNull().sumOf { it.charges }, r.chargeTimes.value().count)
        val charging = r.charging.value()
        assertTrue(charging.count > 0)
        assertEquals(charges[0].size, charging.count)
        val groups = charges[0].groupBy { it.pluggedType }.entries.sortedByDescending { it.value.size }
        assertEquals(2, groups.size)
        assertEquals(groups.map { ChargeCurves.bandMedians(it.value) }, charging.bands.map { it.medians })
        assertEquals(groups.map { ChargeCurves.plugName(it.key) }, charging.bands.map { it.name })
        assertEquals(groups.map { it.value.size }, charging.bands.map { it.count })
        assertEquals(groups.flatMap { ChargeCurves.bandMedians(it.value).values }.maxOrNull(), charging.longest)
        assertEquals(ChargeCurves.topUpPercent(charges[0]), charging.topUp)
        val nights = r.nights.value()
        assertTrue(idle.size >= 6)
        assertEquals(IdleAnalysis.report(idle), nights.report)
        assertEquals(idle.minByOrNull { it.rate }, nights.best)
        assertEquals(idle.maxByOrNull { it.rate }, nights.worst)
        val unsplit = data.map { BatteryMath.dischargeStretches(it.map(BatteryPoint::of), gaps = GapLimits(gaps.stepMs)) }
        assertNotEquals("The 4.5-hour gap should end a phone stretch early", unsplit[0], stretches[0])
        val plan = r.planner.value()
        assertEquals(listOf("Phone", "Watch"), plan.inputs.map { it.name })
        plan.inputs.forEachIndexed { i, input ->
            assertEquals(stretches[i].map { it.drop / it.hours }, input.rates)
            assertEquals(data[i].last().percent, input.percent)
            val rate = BatteryMath.median(charges[i].filter { it.peakMs - it.startMs >= 10 * 60_000L }
                .map { (it.toPct - it.fromPct) / ((it.peakMs - it.startMs) / 60_000.0) })
            assertEquals(rate, input.chargeRate)
            assertEquals(stretches[i].map { it.hours }, input.hours)
            assertEquals(r.chargeTimes.value().times[i]?.steps.orEmpty(), input.chargeSteps)
            assertTrue(input.chargeSteps.isNotEmpty())
        }
        for (hours in listOf(8, 12, 18, 24)) {
            val assessed = plan.inputs.map { ReadinessPlanner.assess(it.name, it.percent, it.rates, it.chargeRate, hours.toDouble(), 15, it.hours, it.chargeSteps) }
            assertEquals(assessed, plan.assessed[hours])
            assertEquals(ReadinessPlanner.order(assessed), plan.order[hours])
        }
        val forecasts = r.forecasts.value()
        assertEquals(ForecastScoring.reportCard(raw.forecasts.value()), forecasts.cards.single().third)
        assertTrue(forecasts.scored > 0)
        assertEquals(1, forecasts.pending)
        assertEquals(2, forecasts.count)
        val journal = r.journal.value()
        assertEquals(12, journal.rows.size)
        journal.rows.forEach { row ->
            assertEquals(UpdateImpactAnalysis.measures(row.entry), row.measured)
            if (row.entry.category != "note" && row.measured) {
                assertEquals(UpdateImpactAnalysis.assess(row.entry, idle), row.impact)
                assertTrue(row.impact!!.before.size >= 3 && row.impact.after.size >= 3)
            } else assertNull(row.impact)
        }
        val matrix = HeatExposure.matrix(phone, gaps).map { it.toList() }
        assertEquals(matrix, r.heat.value().matrix)
        assertEquals(matrix.sumOf { it.sum() }, r.heat.value().total, 1e-9)
        assertTrue(r.heat.value().total >= 1)
        assertEquals(StatsComparison.matched(data, gaps), r.comparison.value())
        assertNotNull(r.comparison.value())
        assertEquals("", r.leftOutCaption.value())
        assertEquals(stretches.map { DrainSpreadMath.of(it, now - 30 * day) }, r.spread.value())
        assertTrue(r.spread.value().all { it != null })
        assertEquals(stretches.map { WeekSplitMath.of(it, now - 30 * day, ::weekend) }, r.week.value())
        assertTrue(r.week.value().all { it?.weekdays != null && it.weekends != null })
        val windows = listOf("24 HOURS" to day, "7 DAYS" to 7 * day, "30 DAYS" to 30 * day, "90 DAYS" to 90 * day,
            "6 MONTHS" to 182 * day, "1 YEAR" to 365 * day, "ALL TIME" to Long.MAX_VALUE / 2)
        assertEquals(stretches.mapIndexed { i, list -> windows.mapNotNull { (label, span) ->
            val overlaps = BatteryMath.overlapping(list, now - span)
            val (hours, drop) = BatteryMath.windowTotals(list, now - span)
            if (overlaps.isEmpty() || drop <= 0) null else StatsPrecomputer.RollingRow(label, 100.0 * hours / drop, overlaps.size,
                if (now - data[i].first().timeMs < span && span < Long.MAX_VALUE / 2) (now - data[i].first().timeMs) / day.toDouble() else null)
        } }, r.rolling.value())
        assertTrue(r.rolling.value().all { it.isNotEmpty() })
        assertEquals(BatteryStats.temperature(phone), r.temperature.value())
        assertNotNull(r.temperature.value())
        assertEquals(data.map { readings -> windows.take(3).mapNotNull { (label, span) ->
            CoverageMath.of(readings.map { it.timeMs }, now - span, now, gaps.stepMs)?.let { label to it }
        } }, r.coverage.value())
        assertTrue(r.coverage.value().all { it.isNotEmpty() })
        assertEquals(Overhead.summarize(raw.probeEvents.value()), r.overhead.value())
        assertTrue(r.overhead.value().polls > 0)
        assertEquals(StatsCapabilities.batteryOnly(raw.capabilities.value()), r.capabilities.value().rows)
        assertEquals(1, r.capabilities.value().verified)
        val drain = r.drain.value()!!
        assertEquals(DrainAnalysis.analyze(phone.map(BatteryPoint::of), usage.screenOn, usage.apps, gaps.stepMs, r.health.value().headlineMah, usage.services), drain.d)
        assertTrue(drain.candidates.size > 8)
        assertEquals(drain.d.apps.map { it.app }.toSet(), drain.labels.keys)
        assertTrue(drain.candidates.all { drain.labels[it] == "Label $it" })
        assertEquals(2, drain.hidden)
        val funFacts = r.funFacts.value()!!
        assertEquals(BatteryStats.wattHours(r.stats.value()[0].percentUsed, r.health.value().phoneCapacity.estimateMah ?: capacity.combinedMah!!), funFacts.wh, 1e-9)
        assertTrue(funFacts.wh > 0)
        assertEquals((now - phone.first().timeMs) / day.toDouble(), funFacts.days, 1e-9)
        assertEquals(18.0, funFacts.rate, 0.0)
        assertTrue(funFacts.rateIsSet)
    }

    @Test fun oneThrowingSectionDoesNotFailTheOthers() {
        val result = StatsPrecomputer.compute(raw().copy(appLabel = { throw IllegalStateException("Sample failure") }), now)
        assertTrue(result.drain is SectionResult.Failed)
        val sections = listOf(result.health, result.stats, result.audit, result.chargeTimes, result.charging, result.nights,
            result.planner, result.forecasts, result.journal, result.heat, result.comparison, result.leftOutCaption, result.rolling,
            result.spread, result.week, result.temperature, result.coverage, result.overhead, result.capabilities, result.funFacts)
        assertTrue(sections.all { it is SectionResult.Ok })
    }

    @Test fun failedReadStaysDifferentFromNoData() {
        val input = raw().copy(journal = SectionResult.capture { error("Sample read failure") })
        assertTrue(StatsPrecomputer.compute(input, now).journal is SectionResult.Failed)
        val empty = raw().copy(data = listOf(emptyList(), emptyList(), emptyList()), usage = ok(null),
            journal = ok(emptyList()), forecasts = ok(emptyList()), phoneWidget = ok(emptyList()), watchCompanion = ok(emptyList()),
            probeEvents = ok(emptyList()), capabilities = ok(emptyList()))
        val result = StatsPrecomputer.compute(empty, now)
        assertTrue(result.journal.value().rows.isEmpty())
        assertNull(result.drain.value())
        assertTrue(result.nights.value().report.stretches.isEmpty())
        assertEquals(0, result.charging.value().count)
        assertTrue(result.chargeTimes.value().times.all { it == null })
        assertTrue(result.planner.value().inputs.isEmpty())
        assertEquals(0, result.forecasts.value().scored)
        assertNull(result.temperature.value())
    }
}
