package com.batterylab.personal

import kotlin.math.ceil

/**
 * How much a device's drain rate varies between discharge stretches (audit #4 roadmap).
 * Rates are percent per hour; [bands] counts stretches per [DrainSpreadMath.BAND_LABELS] band.
 */
data class DrainSpread(val stretches: Int, val median: Double, val p10: Double, val p90: Double,
                       val calmest: DischargeStretch, val hungriest: DischargeStretch, val bands: List<Int>)

object DrainSpreadMath {
    const val MIN_STRETCHES = 5
    /** Upper edges of every band but the last, in percent per hour. */
    private val EDGES = listOf(1.0, 2.0, 4.0, 8.0)
    val BAND_LABELS = listOf("under 1", "1 to 2", "2 to 4", "4 to 8", "8 or more")

    /**
     * Spread over the stretches that end at or after [fromMs]. Null with fewer than
     * [MIN_STRETCHES]. Percentiles use the nearest-rank method: the value at position
     * ceil(p * n / 100) of the sorted rates.
     */
    fun of(stretches: List<DischargeStretch>, fromMs: Long): DrainSpread? {
        val inWindow = stretches.filter { it.endMs >= fromMs && it.hours > 0 }
        if (inWindow.size < MIN_STRETCHES) return null
        val rates = inWindow.map { it.percentPerHour }.sorted()
        val bands = MutableList(EDGES.size + 1) { 0 }
        rates.forEach { r -> bands[EDGES.indexOfFirst { r < it }.let { if (it < 0) EDGES.size else it }]++ }
        return DrainSpread(inWindow.size, rank(rates, 50), rank(rates, 10), rank(rates, 90),
            inWindow.minBy { it.percentPerHour }, inWindow.maxBy { it.percentPerHour }, bands)
    }

    fun rank(sorted: List<Double>, p: Int): Double =
        sorted[(ceil(p * sorted.size / 100.0).toInt()).coerceIn(1, sorted.size) - 1]
}
