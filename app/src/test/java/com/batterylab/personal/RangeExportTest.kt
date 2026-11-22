package com.batterylab.personal

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RangeExportTest {
    private val zone = ZoneId.of("America/New_York")
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()
    private val all = RangeExport.Bounds(null, null)
    private fun csv(rows: List<Reading>, names: Boolean = false, bounds: RangeExport.Bounds = all): String =
        StringWriter().also { writer ->
            val encoder = RangeExport.Encoder(writer, RangeExport.Request(bounds, zone, names))
            rows.forEach(encoder::write)
        }.toString()
    private fun records(csv: String): List<List<String>> {
        val input = StringReader(csv).buffered()
        return buildList {
            while (true) {
                val record = CsvCodec.readRecord(input) ?: break
                if (!record.startsWith("#")) add(CsvCodec.parseLine(record))
            }
        }
    }

    @Test fun rollingPresetsUseCapturedNowAndExactExclusiveBoundaries() {
        val now = ms("2024-03-11T05:00:00Z")
        for ((preset, hours) in listOf(RangeExport.Preset.DAY to 24, RangeExport.Preset.WEEK to 168, RangeExport.Preset.MONTH to 720)) {
            val bounds = RangeExport.resolve(preset, now, zone)
            assertEquals(now - hours * 3_600_000L, bounds.fromMs)
            assertEquals(now, bounds.toMs)
            assertFalse(bounds.contains(bounds.fromMs!! - 1))
            assertTrue(bounds.contains(bounds.fromMs))
            assertTrue(bounds.contains(now - 1))
            assertFalse(bounds.contains(now))
        }
    }

    @Test fun customLeapDayIncludesBothLocalDates() {
        val bounds = RangeExport.resolve(RangeExport.Preset.CUSTOM, 0, zone,
            LocalDate.parse("2024-02-28"), LocalDate.parse("2024-02-29"))
        assertEquals(ms("2024-02-28T05:00:00Z"), bounds.fromMs)
        assertEquals(ms("2024-03-01T05:00:00Z"), bounds.toMs)
        assertFalse(bounds.contains(bounds.toMs!!))
    }

    @Test fun customGapDayHas23HoursAndFoldDay25Hours() {
        for ((day, hours) in listOf("2024-03-10" to 23, "2024-11-03" to 25)) {
            val date = LocalDate.parse(day)
            val b = RangeExport.resolve(RangeExport.Preset.CUSTOM, 0, zone, date, date)
            assertEquals(hours * 3_600_000L, b.toMs!! - b.fromMs!!)
        }
    }

    @Test fun localTimestampsHaveMillisecondsAndNumericOffsetsAcrossGapAndFold() {
        for ((utc, local) in listOf(
            "2024-03-10T06:59:59.123Z" to "2024-03-10T01:59:59.123-05:00",
            "2024-03-10T07:00:00.000Z" to "2024-03-10T03:00:00.000-04:00",
            "2024-11-03T05:30:00.000Z" to "2024-11-03T01:30:00.000-04:00",
            "2024-11-03T06:30:00.000Z" to "2024-11-03T01:30:00.000-05:00")) {
            assertEquals(local, RangeExport.local(ms(utc), zone))
            assertEquals(utc, RangeExport.utc(ms(utc)))
        }
        assertTrue(RangeExport.local(0, ZoneId.of("UTC")).endsWith("+00:00"))
    }

    @Test fun allIsUnboundedAndEmptyHasPreambleAndColumns() {
        assertEquals(all, RangeExport.resolve(RangeExport.Preset.ALL, 123, zone))
        assertTrue(all.contains(Long.MIN_VALUE)); assertTrue(all.contains(Long.MAX_VALUE))
        val output = csv(emptyList())
        assertTrue(output.startsWith(RangeExport.MARKER + "\n"))
        assertTrue(output.contains("# full_backup=false\n# restorable=false\n"))
        assertTrue(output.contains("# from_utc=unbounded"))
        assertEquals(listOf(RangeExport.columns), records(output))
        assertFalse(RangeExport.Bounds(5, 5).contains(5))
    }

    @Test fun reversedAndMissingCustomDatesAreRejected() {
        for ((from, to) in listOf(LocalDate.of(2024, 3, 2) to LocalDate.of(2024, 3, 1), null to null)) {
            assertThrows(IllegalArgumentException::class.java) {
                RangeExport.resolve(RangeExport.Preset.CUSTOM, 0, zone, from, to)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { RangeExport.Bounds(2, 1) }
    }

    @Test fun savedRequestsKeepFrozenBoundsZoneAndOptInAcrossRecreation() {
        val now = ms("2024-11-03T06:30:00Z")
        for (preset in RangeExport.Preset.entries) for (names in listOf(false, true)) {
            val bounds = RangeExport.resolve(preset, now, zone, LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 10))
            val original = RangeExport.Request(bounds, zone, names)
            assertEquals(original, RangeExport.Request.restore(original.saved()))
        }
    }

    @Test fun missingOrCorruptRequestsNeverFallBackToAllHistory() {
        for (saved in listOf("", "1", "1|||UTC|false", "1|invalid|123|UTC|false", "1|1|2|UTC|maybe", "1|2|1|UTC|false"))
            assertThrows(IllegalArgumentException::class.java) { RangeExport.Request.restore(saved) }
    }

    @Test fun pageSqlKeepsCutoffAndTimestampPredicatesOnEveryPage() {
        val bounds = RangeExport.Bounds(100, 200)
        for (after in listOf(-1L, 500L, 1000L)) {
            val page = RangeExport.Page(bounds, 1500, after)
            assertEquals("SELECT * FROM readings WHERE id>? AND id<=? AND time_ms>=? AND time_ms<? ORDER BY id LIMIT 500", page.sql)
            assertEquals(listOf(after.toString(), "1500", "100", "200"), page.args.toList())
        }
        assertEquals("SELECT * FROM readings WHERE id>? AND id<=? ORDER BY id LIMIT 500", RangeExport.Page(all, -1, -1).sql)
        assertThrows(IllegalArgumentException::class.java) { RangeExport.Page(all, 1, 0, 0) }
    }

    @Test fun nullsAndRawUnitsStayDistinctFromZeroAndDerivedFieldsAreOmitted() {
        val row = Reading("phone", 0, 50, false, "android", -15, 1, 0,
            ReadingDetail(currentNowRaw = -123, currentAvgRaw = 0, plugged = 1, energyRaw = 987,
                elapsedMs = 456, trigger = "manual", androidChargeMs = 999,
                qualityFlags = 999, rawExtra = "synthetic-private-payload", thermalHeadroomTenths = 12))
        val output = csv(listOf(row, row.copy(charging = null, tempTenthsC = null, detail = null)))
        val records = records(output)
        val first = records[0].zip(records[1]).toMap()
        val second = records[0].zip(records[2]).toMap()
        assertEquals("false", first["charging"]); assertEquals("", second["charging"])
        assertEquals("-1.5", first["temp_c"]); assertEquals("", second["temp_c"])
        assertEquals("-123", first["current_now_raw"]); assertEquals("0", first["current_avg_raw"])
        assertEquals("0", first["remaining_uah"]); assertEquals("987", first["energy_raw"])
        assertTrue(output.contains("nominal uA")); assertTrue(output.contains("nominal nWh"))
        for (excluded in listOf("android_charge_ms", "quality_flags", "raw_extra", "drop_pp_per_hour", "forecast", "journal", "experiment", "synthetic-private-payload"))
            assertFalse(excluded, output.contains(excluded))
    }

    @Test fun aliasesAreLocalToEachExportAndKeepDistinctStoredSources() {
        val a = Reading("watch:synthetic-a", 0, 50, null, "wear_os")
        val b = a.copy(device = "watch:synthetic-b", source = "samsung_widget")
        val rows = records(csv(listOf(a, b, a)))
        assertEquals(listOf("watch_1", "watch_2", "watch_1"), rows.drop(1).map { it[2] })
        assertEquals(listOf("wear_os", "samsung_widget", "wear_os"), rows.drop(1).map { it[3] })
        assertEquals("watch_1", records(csv(listOf(b)))[1][2])
    }

    @Test fun identifiersAndArbitraryTextNeverLeakEvenWithNamesChecked() {
        // Deliberately synthetic fixtures, never device evidence.
        val secrets = listOf("AA:BB:CC:DD:EE:FF", "serial=SYNTHETIC123456", "custom-private-label", "=1+1", "line\nprivate", "\"quoted,private\"")
        for (names in listOf(false, true)) {
            val rows = secrets.flatMap { secret -> listOf("watch:", "ring:", "phone:", "").map { prefix ->
                Reading(prefix + secret, 0, 50, null, secret, detail = ReadingDetail(trigger = secret, transport = secret, rawExtra = secret))
            } }
            val output = csv(rows, names)
            for (secret in secrets) assertFalse(secret, output.contains(secret))
            val parsed = records(output)
            assertEquals(names, "device_name" in parsed[0])
            assertTrue(parsed.drop(1).all { it[3].isEmpty() && it[20].isEmpty() && it[22].isEmpty() })
            if (names) assertTrue(parsed.drop(1).all { it.last().isEmpty() })
        }
    }

    @Test fun optInUsesOnlyAnExactStoredModelAndNeverInventsOne() {
        val row = Reading("watch:Watch", 0, 50, null, "wear_os")
        assertEquals("Watch", records(csv(listOf(row), true))[1].last())
        assertFalse(csv(listOf(row)).contains("\"Watch\""))
        assertNull(RangeExport.storedName("watch:samsung_widget"))
        assertNull(RangeExport.storedName("phone"))
        assertNull(RangeExport.storedName("watch:Watch serial=SYNTHETIC123"))
    }

    @Test fun csvEscapesMultilineQuotesAndDefusesSpreadsheetFormulas() {
        for (value in listOf("comma,quote\"newline\n", "=1+1", "+1", "-1", "@test", "\t=1", "\r=1", "\n=1", "'=1")) {
            val encoded = CsvCodec.quote(value)
            assertEquals(value, CsvCodec.parseLine(encoded).single())
            if (value.first() in "=+-@\t\r'") assertTrue(encoded.startsWith("\"'"))
        }
    }

    @Test fun firstRecordClassifierPreservesLegacyHeadersAndRejectsAnalysisVersions() {
        for (header in listOf("kind,device,mode_or_source,timestamp_utc,percentage", BatteryStore.EXPORT_COLUMNS.joinToString(",")))
            assertEquals(RangeExport.FirstRecord.BACKUP, RangeExport.classify(header))
        for (version in listOf("1", "2", "unknown"))
            assertEquals(RangeExport.FirstRecord.ANALYSIS, RangeExport.classify("# battery-lab-analysis-export-version=$version"))
        assertEquals(RangeExport.FirstRecord.INVALID, RangeExport.classify("timestamp_utc,percentage"))
        assertEquals(RangeExport.FirstRecord.INVALID, RangeExport.classify(""))
    }
}
