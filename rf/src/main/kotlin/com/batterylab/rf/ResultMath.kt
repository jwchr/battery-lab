package com.batterylab.rf

import java.io.File

/** One raw row reduced to what the result card needs. Computed from obs files, never stored. */
data class Sample(val tBootNs: Long, val rssiDbm: Int?, val ok: Boolean)

/** From a marker to the next marker or the session end. The whole session uses label "". */
data class Window(val label: String, val startNs: Long, val endNs: Long)

data class WindowStats(
    val window: Window,
    val rows: Int,
    val okRows: Int,
    val meanRowsPerSecond: Double,
    /** Whole seconds counted from the window's start; a last partial second is left out. */
    val buckets: Int,
    val medianRowsPerBucket: Int?,
    val medianDbm: Int?,
    val p10Dbm: Int?,
    val p90Dbm: Int?,
) {
    val successShare: Double get() = if (rows == 0) 0.0 else okRows.toDouble() / rows
}

/** A per-session G0 result for one signal, with the numbers behind it. */
data class G0Result(val signal: String, val pass: Boolean, val explanation: String)

class StreamResult(val stream: StreamInfo, val whole: WindowStats?, val windows: List<Pair<Window, WindowStats?>>,
                   val g0: G0Result?)

class SessionResult(val info: SessionInfo, val streams: List<StreamResult>)

/** Result card math (R30), tested by T18. Nearest-rank medians and percentiles on successful values only. */
object ResultMath {
    private const val SECOND_NS = 1_000_000_000L
    const val NEAR = "near"
    const val FAR = "far"

    /** The value at position ceil(p * n / 100) of [sorted], counted from 1. Null when empty. */
    fun <T : Comparable<T>> nearestRank(sorted: List<T>, p: Int): T? {
        if (sorted.isEmpty()) return null
        val n = sorted.size
        val rank = ((p.toLong() * n + 99) / 100).toInt().coerceIn(1, n)
        return sorted[rank - 1]
    }

    /** The nearest-rank median of [total] buckets, of which only [nonZero] (sorted) have rows. */
    private fun medianWithZeros(nonZero: List<Int>, total: Long): Int? {
        if (total <= 0) return null
        val rank = (50L * total + 99) / 100
        val zeros = total - nonZero.size
        return if (rank <= zeros) 0 else nonZero[(rank - zeros - 1).toInt()]
    }

    fun windows(markers: List<Marker>, sessionStartNs: Long, sessionEndNs: Long): List<Window> {
        val inside = markers.filter { it.tBootNs in sessionStartNs until sessionEndNs }.sortedBy { it.tBootNs }
        return inside.mapIndexed { i, m ->
            Window(m.label, m.tBootNs, if (i + 1 < inside.size) inside[i + 1].tBootNs else sessionEndNs)
        }
    }

    fun samples(table: CsvTable): List<Sample> = table.rows.mapNotNull { row ->
        val t = table.cell(row, "t_boot_ns")?.toLongOrNull() ?: return@mapNotNull null
        val v = table.cell(row, "rssi_dbm")?.toIntOrNull()
        Sample(t, v, table.cell(row, "status") == Status.OK && v != null)
    }

    /**
     * A Bluetooth scan holds pinned devices and, in "Everything nearby", every device; its numbers
     * and its G0 test use the Ring's rows only. A session without the alias column is all Ring.
     */
    fun ringOnly(kind: String, table: CsvTable): CsvTable =
        if (kind != Kinds.BLE_SCAN || !table.has(BleScanHub.ALIAS)) table
        else CsvTable(table.header, table.rows.filter { table.cell(it, BleScanHub.ALIAS) == PinStore.RING })

    /** Null for an empty window: no result. */
    fun stats(samples: List<Sample>, w: Window): WindowStats? {
        val inside = samples.filter { it.tBootNs >= w.startNs && it.tBootNs < w.endNs }
        if (inside.isEmpty()) return null
        // An imported file can claim any span, so buckets are counted sparsely: memory follows the
        // number of rows, never the window's length. Overflowing spans count as the longest possible.
        val lengthNs = (w.endNs - w.startNs).let { if (it < 0) Long.MAX_VALUE else it }
        val bucketCount = lengthNs / SECOND_NS
        val counts = HashMap<Long, Int>()
        for (s in inside) {
            val b = (s.tBootNs - w.startNs) / SECOND_NS
            if (b in 0 until bucketCount) counts[b] = (counts[b] ?: 0) + 1
        }
        val values = inside.filter { it.ok }.mapNotNull { it.rssiDbm }.sorted()
        return WindowStats(
            window = w,
            rows = inside.size,
            okRows = inside.count { it.ok },
            meanRowsPerSecond = if (lengthNs > 0) inside.size * SECOND_NS.toDouble() / lengthNs else 0.0,
            buckets = bucketCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            medianRowsPerBucket = medianWithZeros(counts.values.sorted(), bucketCount),
            medianDbm = nearestRank(values, 50),
            p10Dbm = nearestRank(values, 10),
            p90Dbm = nearestRank(values, 90),
        )
    }

    private fun first(windows: List<Pair<Window, WindowStats?>>, label: String) =
        windows.firstOrNull { it.first.label.trim().equals(label, ignoreCase = true) }?.second

    private fun pct(share: Double) = Num.d(share * 100, 1) + "%"

    /** Link signal, this session: at least 90% of reads succeed, and near median is 10 dB or more above far. */
    fun g0Link(whole: WindowStats?, near: WindowStats?, far: WindowStats?): G0Result {
        val reasons = mutableListOf<String>()
        val shareOk = whole != null && whole.okRows.toLong() * 10 >= whole.rows.toLong() * 9
        reasons += if (whole == null) "no reads" else "${whole.okRows} of ${whole.rows} reads succeeded (${pct(whole.successShare)}, needs 90%)"
        val diff = diff(near, far, reasons)
        return G0Result("link", shareOk && diff != null && diff >= 10, reasons.joinToString("; "))
    }

    /** Broadcast signal, this session: median of 1 or more rows per second near and far, and a 10 dB difference. */
    fun g0Broadcast(near: WindowStats?, far: WindowStats?): G0Result {
        val reasons = mutableListOf<String>()
        val nearRate = near?.medianRowsPerBucket
        val farRate = far?.medianRowsPerBucket
        reasons += "median rows per second near ${nearRate ?: "none"}, far ${farRate ?: "none"} (needs 1 in both)"
        val diff = diff(near, far, reasons)
        val pass = (nearRate ?: 0) >= 1 && (farRate ?: 0) >= 1 && diff != null && diff >= 10
        return G0Result("broadcast", pass, reasons.joinToString("; "))
    }

    private fun diff(near: WindowStats?, far: WindowStats?, reasons: MutableList<String>): Int? {
        val n = near?.medianDbm
        val f = far?.medianDbm
        if (n == null || f == null) {
            reasons += "needs a near and a far window with readings"
            return null
        }
        reasons += "near median $n dBm, far median $f dBm, difference ${n - f} dB (needs 10)"
        return n - f
    }

    fun compute(dir: File): SessionResult {
        val info = SessionReader.read(dir)
        val start = info.startBootNs ?: 0L
        val tables = info.streams.associate { it.id to SessionReader.obs(dir, it.id) }
        val lastRow = tables.values.flatMap { t -> t.rows.mapNotNull { t.cell(it, "t_boot_ns")?.toLongOrNull() } }.maxOrNull()
        val end = info.endBootNs ?: (lastRow?.plus(1) ?: start)
        val windows = windows(SessionReader.markers(dir), start, end)
        val results = info.streams.map { s ->
            val samples = samples(ringOnly(s.kind, tables.getValue(s.id)))
            val whole = stats(samples, Window("", start, end))
            val per = windows.map { it to stats(samples, it) }
            val g0 = when (s.kind) {
                Kinds.RING_LINK -> g0Link(whole, first(per, NEAR), first(per, FAR))
                Kinds.BLE_SCAN -> g0Broadcast(first(per, NEAR), first(per, FAR))
                else -> null
            }
            StreamResult(s, whole, per, g0)
        }
        return SessionResult(info, results)
    }
}
