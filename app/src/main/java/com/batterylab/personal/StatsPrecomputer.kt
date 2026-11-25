package com.batterylab.personal

import java.util.Calendar

internal sealed interface SectionResult<out T> {
    data class Ok<T>(val value: T) : SectionResult<T>
    data class Failed(val cause: Exception) : SectionResult<Nothing>

    companion object {
        fun <T> capture(block: () -> T): SectionResult<T> = try {
            Ok(block())
        } catch (e: Exception) {
            Failed(e)
        }
    }
}

/** History analysis with no Android calls. */
internal object StatsPrecomputer {
    data class Raw(
        val devices: List<Triple<String, String, Int>>,
        val data: List<List<Reading>>,
        val gaps: GapLimits,
        val usage: SectionResult<UsageWindow?>,
        val phoneWidget: SectionResult<List<Reading>>,
        val watchCompanion: SectionResult<List<Reading>>,
        val forecasts: SectionResult<List<Forecast>>,
        val journal: SectionResult<List<JournalEntry>>,
        val probeEvents: SectionResult<List<Pair<Long, String>>>,
        val capabilities: SectionResult<List<CapabilityRow>>,
        val hidden: SectionResult<Int?>,
        val rate: Double,
        val rateIsSet: Boolean,
        val appLabel: (String) -> String,
    )

    data class Health(val capacity: CapacityEstimate, val phoneCapacity: CapacityProxySummary)
    data class Drain(val recent: List<Reading>, val d: DrainBreakdown, val labels: Map<String, String>,
                     val candidates: List<String>, val hidden: Int?)
    data class Audit(val report: HonestyAudit, val phoneCount: Int, val widgetCount: Int, val watchTimes: List<Long>)
    data class ChargeBand(val name: String, val count: Int, val medians: Map<Pair<Int, Int>, Double>)
    data class Charging(val count: Int, val bands: List<ChargeBand>, val longest: Double, val topUp: Double?)
    data class ChargeTiming(val times: List<DeviceChargeTimes?>, val count: Int)
    data class Nights(val report: IdleReport, val best: IdleStretch?, val worst: IdleStretch?)
    data class PlanInput(val name: String, val percent: Int, val rates: List<Double>, val chargeRate: Double?)
    data class Plan(val inputs: List<PlanInput>, val assessed: Map<Int, List<DeviceReadiness>>,
                    val order: Map<Int, List<DeviceReadiness>>)
    data class Forecasts(val cards: List<Triple<String, String, List<HorizonStats>>>, val count: Int,
                         val pending: Int, val scored: Int, val model: String)
    data class JournalRow(val entry: JournalEntry, val measured: Boolean, val impact: UpdateImpact?)
    data class Journal(val rows: List<JournalRow>, val settleHours: Int)
    data class RollingRow(val label: String, val hours: Double, val count: Int, val daysSoFar: Double?)
    data class Heat(val matrix: List<List<Double>>, val total: Double, val most: Double,
                    val chargeBands: List<String>, val tempBands: List<String>)
    data class Capabilities(val rows: List<CapabilityRow>, val verified: Int)
    data class Fun(val wh: Double, val fullCharge: Double, val days: Double, val rate: Double, val rateIsSet: Boolean)
    data class Result(
        val now: Long,
        val devices: List<Triple<String, String, Int>>,
        val gaps: GapLimits,
        val readingCount: Int,
        val health: SectionResult<Health>,
        val stats: SectionResult<List<DeviceStats>>,
        val drain: SectionResult<Drain?>,
        val audit: SectionResult<Audit>,
        val chargeTimes: SectionResult<ChargeTiming>,
        val charging: SectionResult<Charging>,
        val nights: SectionResult<Nights>,
        val planner: SectionResult<Plan>,
        val forecasts: SectionResult<Forecasts>,
        val journal: SectionResult<Journal>,
        val heat: SectionResult<Heat>,
        val comparison: SectionResult<MatchedStats?>,
        val leftOutCaption: SectionResult<String>,
        val rolling: SectionResult<List<List<RollingRow>>>,
        val spread: SectionResult<List<DrainSpread?>>,
        val week: SectionResult<List<WeekSplit?>>,
        val temperature: SectionResult<TempStats?>,
        val coverage: SectionResult<List<List<Pair<String, Coverage>>>>,
        val overhead: SectionResult<Overhead.Summary>,
        val capabilities: SectionResult<Capabilities>,
        val funFacts: SectionResult<Fun?>,
    )

    private fun <T> SectionResult<T>.get(): T = when (this) {
        is SectionResult.Ok -> value
        is SectionResult.Failed -> throw cause
    }

    fun compute(raw: Raw, now: Long): Result {
        val devices = raw.devices.toList()
        val data = raw.data.map { BatteryStats.fullHoldAsCharging(it) }
        val phone = data[0]
        val gaps = raw.gaps
        val day = 86_400_000L
        val hourOf = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY) }
        val isWeekend = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)
            .let { it == Calendar.SATURDAY || it == Calendar.SUNDAY } }
        val health = SectionResult.capture { Health(HonestyAuditor.estimatePhoneCapacity(phone, gaps), CapacityProxy.summarize(phone, now)) }
        val stats = SectionResult.capture { data.map { BatteryStats.device(it, hourOf, gaps) } }
        val stretches = SectionResult.capture { data.map { BatteryMath.dischargeStretches(it.map(BatteryPoint::of), gaps = gaps) } }
        val charges = SectionResult.capture { data.map { ChargeCurves.observedCharges(it, gaps) } }
        val idle = SectionResult.capture { raw.usage.get().let { usage ->
            IdleAnalysis.stretches(phone, usage?.screenOn, gaps = gaps, screenCoverageStartMs = usage?.coverageStartMs)
        } }
        val drain = SectionResult.capture { raw.usage.get()?.let { u ->
            val recent = u.coverageStartMs?.let { start -> phone.filter { it.timeMs >= start } }.orEmpty()
            val d = DrainAnalysis.analyze(recent.map(BatteryPoint::of), u.screenOn, u.apps, gaps.stepMs,
                health.get().capacity.combinedMah, u.services)
            Drain(recent, d, d.apps.associate { it.app to raw.appLabel(it.app) },
                d.apps.map { it.app }.filter { it != DrainAnalysis.UNATTRIBUTED }.take(15), raw.hidden.get())
        } }
        val audit = SectionResult.capture {
            val widget = raw.phoneWidget.get()
            val watch = (data[1] + raw.watchCompanion.get()).sortedBy { it.timeMs }
            Audit(HonestyAuditor.audit(phone, widget, watch, data[2], health.get().capacity.combinedMah, gaps), phone.size, widget.size, watch.map { it.timeMs })
        }
        val chargeTimes = SectionResult.capture {
            val times = data.map { ChargeTimes.analyze(it, gaps) }
            ChargeTiming(times, times.filterNotNull().sumOf { it.charges })
        }
        val charging = SectionResult.capture {
            val list = charges.get()[0]
            val bands = list.groupBy { it.pluggedType }.entries.sortedByDescending { it.value.size }.map { (type, group) ->
                ChargeBand(if (type == null) "Charger type not recorded" else ChargeCurves.plugName(type),
                    group.size, ChargeCurves.bandMedians(group))
            }
            Charging(list.size, bands, bands.flatMap { it.medians.values }.maxOrNull() ?: 1.0, ChargeCurves.topUpPercent(list))
        }
        val nights = SectionResult.capture { idle.get().let {
            Nights(IdleAnalysis.report(it), it.minByOrNull { s -> s.rate }, it.maxByOrNull { s -> s.rate })
        } }
        val planner = SectionResult.capture {
            val allStretches = stretches.get()
            val allCharges = charges.get()
            val inputs = devices.zip(data).mapIndexedNotNull { i, (device, readings) ->
                val last = readings.lastOrNull()?.takeIf { now - it.timeMs < 6 * 3_600_000L } ?: return@mapIndexedNotNull null
                val chargeRate = BatteryMath.median(allCharges[i].filter { it.peakMs - it.startMs >= 10 * 60_000L }
                    .map { (it.toPct - it.fromPct) / ((it.peakMs - it.startMs) / 60_000.0) })
                PlanInput(device.second, last.percent, allStretches[i].map { it.drop / it.hours }, chargeRate)
            }
            val assessed = listOf(8, 12, 18, 24).associateWith { hours -> inputs.map {
                ReadinessPlanner.assess(it.name, it.percent, it.rates, it.chargeRate, hours.toDouble(), 15)
            } }
            Plan(inputs, assessed, assessed.mapValues { ReadinessPlanner.order(it.value) })
        }
        val forecasts = SectionResult.capture {
            val all = raw.forecasts.get()
            val cards = all.groupBy { it.device }.map { (id, list) ->
                val name = devices.firstOrNull { it.first == id }?.second ?: when {
                    id.startsWith("watch") -> "Watch"; id.startsWith("ring") -> "Ring"; else -> "Phone"
                }
                Triple(id, name, ForecastScoring.reportCard(list))
            }.sortedBy { if (it.first == "phone") 0 else 1 }
            Forecasts(cards, all.size, all.count { it.status == "PENDING" }, cards.sumOf { it.third.sumOf { h -> h.n } }, ForecastScoring.MODEL_VERSION)
        }
        val journal = SectionResult.capture {
            Journal(raw.journal.get().take(12).map { e ->
                val measured = UpdateImpactAnalysis.measures(e)
                JournalRow(e, measured, if (e.category != "note" && measured) UpdateImpactAnalysis.assess(e, idle.get()) else null)
            }, UpdateImpactAnalysis.SETTLE_HOURS)
        }
        val heat = SectionResult.capture {
            val matrix = HeatExposure.matrix(phone, gaps).map { it.toList() }
            Heat(matrix, matrix.sumOf { it.sum() }, matrix.maxOf { it.maxOrNull() ?: 0.0 }.coerceAtLeast(0.01),
                HeatExposure.CHARGE_BANDS.toList(), HeatExposure.TEMP_BANDS.toList())
        }
        val comparison = SectionResult.capture { StatsComparison.matched(data, gaps) }
        val leftOut = SectionResult.capture {
            val names = comparison.get()?.let { c -> devices.indices.filter { data[it].isNotEmpty() && it !in c.deviceIndexes }
                .joinToString(", ") { devices[it].second } }.orEmpty()
            if (names.isEmpty()) "" else " Left out: $names, not enough shared history yet."
        }
        val rolling = SectionResult.capture { stretches.get().mapIndexed { i, list ->
            val windows = listOf("24 HOURS" to day, "7 DAYS" to 7 * day, "30 DAYS" to 30 * day, "90 DAYS" to 90 * day,
                "6 MONTHS" to 182 * day, "1 YEAR" to 365 * day, "ALL TIME" to Long.MAX_VALUE / 2)
            if (list.isEmpty()) emptyList() else windows.mapNotNull { (label, span) ->
                val inWindow = BatteryMath.overlapping(list, now - span)
                val (hours, drop) = BatteryMath.windowTotals(list, now - span)
                if (inWindow.isEmpty() || drop <= 0) null else RollingRow(label, 100.0 * hours / drop, inWindow.size,
                    if (now - data[i].first().timeMs < span && span < Long.MAX_VALUE / 2) (now - data[i].first().timeMs) / day.toDouble() else null)
            }
        } }
        val spread = SectionResult.capture { stretches.get().map { DrainSpreadMath.of(it, now - 30 * day) } }
        val week = SectionResult.capture { stretches.get().map { WeekSplitMath.of(it, now - 30 * day, isWeekend) } }
        val coverage = SectionResult.capture { data.map { readings ->
            listOf("24 HOURS" to day, "7 DAYS" to 7 * day, "30 DAYS" to 30 * day).mapNotNull { (label, span) ->
                CoverageMath.of(readings.map { it.timeMs }, now - span, now, gaps.stepMs)?.let { label to it }
            }
        } }
        val funFacts = SectionResult.capture {
            val capacity = health.get().let { it.phoneCapacity.estimateMah ?: it.capacity.combinedMah }
            capacity?.let { cap -> Fun(BatteryStats.wattHours(stats.get()[0].percentUsed, cap), BatteryStats.wattHours(100, cap),
                phone.firstOrNull()?.let { (now - it.timeMs) / day.toDouble() } ?: 0.0, raw.rate, raw.rateIsSet) }
        }
        return Result(now, devices, gaps, data.sumOf { it.size }, health, stats, drain, audit, chargeTimes, charging,
            nights, planner, forecasts, journal, heat, comparison, leftOut, rolling, spread, week,
            SectionResult.capture { BatteryStats.temperature(phone) }, coverage,
            SectionResult.capture { Overhead.summarize(raw.probeEvents.get()) },
            SectionResult.capture { StatsCapabilities.batteryOnly(raw.capabilities.get()).let { rows ->
                Capabilities(rows, rows.count { it.status == CapabilityProbe.VERIFIED })
            } }, funFacts)
    }
}
