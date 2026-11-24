package com.batterylab.personal

import java.util.Locale
import kotlin.math.abs

data class ArmDetail(
    val mode: String,
    val runs: Int,
    val setAside: Int,
    val medianRate: Double?,
    val minRate: Double?,
    val maxRate: Double?,
    val meanHours: Double?,
    val meanStartPct: Double?,
    val source: String,
)

/** Uses the same accepted rates as ExperimentStats.compare, including fitted rates when available. */
fun armDetail(results: List<ExperimentResult>, mode: String): ArmDetail {
    val arm = results.filter { it.experiment.mode == mode }
    val counted = arm.filter { it.rate != null }
    val rates = counted.mapNotNull { it.rate }
    val hours = counted.mapNotNull { r ->
        r.experiment.endMs?.let { (it - r.experiment.startMs) / 3_600_000.0 }
    }
    return ArmDetail(
        mode, counted.size, arm.count { it.experiment.endMs != null && it.quarantine != null },
        BatteryMath.median(rates), rates.minOrNull(), rates.maxOrNull(),
        hours.takeIf { it.isNotEmpty() }?.average(),
        counted.takeIf { it.isNotEmpty() }?.map { it.experiment.startPct.toDouble() }?.average(),
        // Endpoint.choose can accept widget/manual endpoints even for monitoring. This label
        // describes the arm's intended rate series, as selected by ExperimentStats.usesSource.
        if (ExperimentEndpoint.allowsBle(mode)) "direct Ring reads" else "Samsung's widget",
    )
}

fun armDetailText(arm: ArmDetail): String = buildString {
    fun f(value: Double) = String.format(Locale.US, "%.2f", value)
    if (arm.runs == 0 || arm.medianRate == null) {
        append("${modeName(arm.mode)}: 0 runs, from ${arm.source}.")
    } else {
        append("${modeName(arm.mode)}: ${arm.runs} runs, median ${f(arm.medianRate)} pp/hour " +
            "(${arm.minRate?.let(::f)} to ${arm.maxRate?.let(::f)}), ")
        arm.meanHours?.let { append("about ${f(it)} h each, ") }
        append("from ${arm.source}.")
    }
    if (arm.setAside > 0) append(" ${arm.setAside} set aside.")
}

enum class OrderCheck { ALTERNATED, BLOCKED, TOO_FEW }

/** Any interleaving counts as alternated here; this is not a check for a strict ABBA sequence. */
fun order(results: List<ExperimentResult>, a: String, b: String): OrderCheck {
    val counted = results.filter { it.rate != null && it.experiment.mode in listOf(a, b) }
        .sortedBy { it.experiment.startMs }
    val nA = counted.count { it.experiment.mode == a }
    val nB = counted.count { it.experiment.mode == b }
    if (nA < 2 || nB < 2) return OrderCheck.TOO_FEW
    val changes = counted.zipWithNext().count { (x, y) -> x.experiment.mode != y.experiment.mode }
    return if (changes == 1) OrderCheck.BLOCKED else OrderCheck.ALTERNATED
}

fun conditionWarnings(a: ArmDetail, b: ArmDetail): List<String> = buildList {
    val aHours = a.meanHours
    val bHours = b.meanHours
    if (aHours != null && bHours != null && abs(aHours - bHours) > minOf(aHours, bHours) * 0.5) {
        val longer = if (aHours > bHours) a else b
        val shorter = if (aHours > bHours) b else a
        add("${modeName(longer.mode)} runs were much longer than ${modeName(shorter.mode)} runs; " +
            "drain is not always steady, so compare like with like.")
    }
    val aStart = a.meanStartPct
    val bStart = b.meanStartPct
    if (aStart != null && bStart != null && abs(aStart - bStart) > 15) {
        val higher = if (aStart > bStart) a else b
        val lower = if (aStart > bStart) b else a
        add("${modeName(higher.mode)} runs started at a much higher charge than ${modeName(lower.mode)} runs; " +
            "compare runs starting near the same charge.")
    }
}

fun modeName(mode: String) = when (mode) {
    "baseline" -> "Baseline"; "monitoring" -> "Monitored"; "ring_alone" -> "Ring alone"; "ring_with_watch" -> "Ring + Watch"
    else -> mode
}
