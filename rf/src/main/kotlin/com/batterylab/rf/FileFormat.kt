package com.batterylab.rf

import java.util.Locale

/**
 * File rules (R29): UTF-8, line feed endings, numbers in Locale.ROOT, a header row in every CSV.
 * Readers find columns by name and keep unknown columns and keys. A last line with no line
 * ending is a torn write and is ignored. A missing value is an empty cell, never zero.
 */
object FileNames {
    const val PROPERTIES = "session.properties"
    const val STREAMS = "streams.csv"
    const val EVENTS = "events.csv"
    const val EMITTERS = "emitters.csv"
    val FIXED = setOf(PROPERTIES, STREAMS, EVENTS, EMITTERS)

    private val streamName = Regex("[a-z0-9_]{1,24}")
    private val obsName = Regex("obs_([a-z0-9_]{1,24})\\.csv")
    private val binName = Regex("bin_([a-z0-9_]{1,24})\\.dat")

    fun validStreamId(id: String) = streamName.matches(id)
    fun obs(streamId: String) = "obs_$streamId.csv"
    fun bin(streamId: String) = "bin_$streamId.dat"

    /** The import allowlist in R16: four fixed names, obs_<name>.csv and bin_<name>.dat. */
    fun allowed(name: String) = name in FIXED || obsName.matches(name) || binName.matches(name)

    fun streamOfObs(name: String) = obsName.matchEntire(name)?.groupValues?.get(1)
    fun streamOfBin(name: String) = binName.matchEntire(name)?.groupValues?.get(1)
}

object Num {
    fun d(value: Double, digits: Int = 3): String = String.format(Locale.ROOT, "%.${digits}f", value)
    fun opt(value: Int?): String = value?.toString() ?: ""
    fun opt(value: Long?): String = value?.toString() ?: ""
}

/**
 * Free text (marker labels, details) is always quoted. Text a spreadsheet would run as a formula
 * gets a leading apostrophe on write, which the reader takes off again, so it round-trips.
 */
object FreeText {
    private val risky = charArrayOf('=', '+', '-', '@', '\t', '\r', '\'')
    fun encode(text: String) = if (text.isNotEmpty() && text[0] in risky) "'$text" else text
    fun decode(cell: String) = if (cell.startsWith("'")) cell.substring(1) else cell
}

class CsvTable(val header: List<String>, val rows: List<List<String>>) {
    fun has(name: String) = header.indexOf(name) >= 0

    /** The cell under [name], or null when the column is absent. An empty cell stays "". */
    fun cell(row: List<String>, name: String): String? {
        val i = header.indexOf(name)
        return if (i < 0) null else row.getOrElse(i) { "" }
    }

    fun map(row: List<String>): LinkedHashMap<String, String> =
        LinkedHashMap<String, String>().also { m -> header.forEachIndexed { i, h -> m[h] = row.getOrElse(i) { "" } } }
}

object Csv {
    private fun needsQuote(v: String) = v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }

    fun cell(value: String, quote: Boolean = false): String =
        if (quote || needsQuote(value)) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** One CSV record with its line feed. Columns in [quoted] are always quoted. */
    fun line(cells: List<String>, quoted: Set<Int> = emptySet()): String =
        cells.mapIndexed { i, v -> cell(v, i in quoted) }.joinToString(",") + "\n"

    /** Parses [text]. A final record with no line ending is ignored as a torn write. */
    fun parse(text: String): CsvTable {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else inQuotes = false
                } else cur.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { fields.add(cur.toString()); cur.setLength(0) }
                '\r' -> if (i + 1 >= text.length || text[i + 1] != '\n') cur.append(c)
                '\n' -> {
                    fields.add(cur.toString()); cur.setLength(0)
                    if (!(fields.size == 1 && fields[0].isEmpty())) records.add(fields)
                    fields = mutableListOf()
                }
                else -> cur.append(c)
            }
            i++
        }
        // Whatever is left had no line ending: a torn last line.
        if (records.isEmpty()) return CsvTable(emptyList(), emptyList())
        return CsvTable(records[0], records.drop(1))
    }
}

/** key=value lines in a fixed order. Unknown keys are kept. */
object Props {
    fun clean(value: String) = value.replace('\n', ' ').replace('\r', ' ')

    fun write(map: Map<String, String>): String =
        map.entries.joinToString("") { (k, v) -> "$k=${clean(v)}\n" }

    fun parse(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val complete = text.substring(0, text.lastIndexOf('\n') + 1)
        for (raw in complete.split('\n')) {
            val line = raw.removeSuffix("\r")
            if (line.isBlank() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            out[line.substring(0, eq).trim()] = line.substring(eq + 1)
        }
        return out
    }
}
