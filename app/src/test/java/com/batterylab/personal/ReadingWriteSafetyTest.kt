package com.batterylab.personal

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReadingWriteSafetyTest {
    @Test fun anotherWritersBackdatedRowAfterFirstChunkIsSkipped() {
        val keys = ReadingImportDedupe()
        keys.includeCommitted(10, "phone", "android", 100)
        assertTrue(keys.add("phone", "android", 200))
        // The next transaction scans both our committed row and another writer's older sample.
        keys.includeCommitted(11, "phone", "android", 200)
        keys.includeCommitted(12, "watch:synthetic", "wear_os", 50)
        assertFalse(keys.add("watch:synthetic", "wear_os", 50))
        assertEquals(12L, keys.watermark)
    }

    @Test fun importsOwnRowsStayKnownAcrossRefreshes() {
        val keys = ReadingImportDedupe()
        assertTrue(keys.add("phone", "android", 100))
        assertFalse(keys.add("phone", "android", 100))
        keys.includeCommitted(1, "phone", "android", 100)
        assertFalse(keys.add("phone", "android", 100))
        keys.includeCommitted(2, "phone", "android", 200)
        assertFalse(keys.add("phone", "android", 100))
    }

    @Test fun emptyTableAcceptsFirstRowAndKeysKeepAllThreeFields() {
        val keys = ReadingImportDedupe()
        assertEquals(0L, keys.watermark)
        assertTrue(keys.add("phone", "android", 100))
        assertFalse(keys.add("phone", "android", 100))
        assertTrue(keys.add("phone", "samsung_widget", 100))
        assertTrue(keys.add("watch:synthetic", "android", 100))
        assertTrue(keys.add("phone", "android", 101))
        assertEquals(0L, keys.watermark) // CSV timestamps are not database ids.
    }

    @Test fun watermarkOnlyMovesForwardEvenWithUnorderedRefreshRows() {
        val keys = ReadingImportDedupe()
        keys.includeCommitted(20, "phone", "android", 200)
        keys.includeCommitted(8, "phone", "android", 80)
        assertEquals(20L, keys.watermark)
        keys.includeCommitted(20, "phone", "android", 200)
        assertEquals(20L, keys.watermark)
        assertFalse(keys.add("phone", "android", 80))
        assertTrue(keys.add("phone", "android", 1000))
        assertEquals(20L, keys.watermark)
        keys.includeCommitted(21, "phone", "android", 50)
        assertEquals(21L, keys.watermark)
    }

    private fun source(name: String) = listOf(
        File("src/main/java/com/batterylab/personal/$name.kt"),
        File("app/src/main/java/com/batterylab/personal/$name.kt")
    ).first { it.isFile }.readText()

    @Test fun widgetDuplicateCheckUsesSameSourceLatest() {
        val record = source("SamsungWidgetSource").substringAfter("fun record(").substringBefore("private fun recognizePhoneSlot")
        assertFalse(Regex("store\\.latest\\s*\\(").containsMatchIn(record))
        assertTrue(record.contains("store.latestFrom(device, SOURCE)"))
        assertTrue(record.contains("last != null && WidgetRepeat.isRecent(last, reading, DUPLICATE_WINDOW_MS)"))
    }

    private val last = Reading("ring:synthetic", 1_000_000, 50, false, "samsung_widget")

    @Test fun sameValueInsideTheWindowIsARepeat() {
        assertTrue(WidgetRepeat.isRecent(last, last.copy(timeMs = last.timeMs), 300_000))
        assertTrue(WidgetRepeat.isRecent(last, last.copy(timeMs = last.timeMs + 299_999), 300_000))
        assertFalse(WidgetRepeat.isRecent(last, last.copy(timeMs = last.timeMs + 300_000), 300_000))
        assertFalse(WidgetRepeat.isRecent(null, last, 300_000))
    }

    @Test fun changedValueOrOtherSourceIsNotARepeat() {
        assertFalse(WidgetRepeat.isRecent(last, last.copy(timeMs = last.timeMs + 1, percent = 49), 300_000))
        assertFalse(WidgetRepeat.isRecent(last, last.copy(timeMs = last.timeMs + 1, charging = true), 300_000))
        assertFalse(WidgetRepeat.isRecent(last.copy(source = "ring_ble"), last.copy(timeMs = last.timeMs + 1), 300_000))
    }

    @Test fun clockSteppedBackwardIsKeptNotTakenForARepeat() {
        assertFalse(WidgetRepeat.isRecent(last, last.copy(timeMs = last.timeMs - 1), 300_000))
        assertFalse(WidgetRepeat.isRecent(last, last.copy(timeMs = last.timeMs - 3_600_000), 300_000))
    }

    @Test fun watchUsesAtomicAppendInsteadOfCheckThenAppend() {
        val receiver = source("WatchReceiver")
        assertFalse(Regex("store\\.has\\s*\\(").containsMatchIn(receiver))
        assertFalse(Regex("store\\.append\\s*\\(").containsMatchIn(receiver))
        assertTrue(receiver.contains("store.appendIfNew(Reading("))
    }

    @Test fun bothAppendPathsAssessAndInsertUnderOneTransaction() {
        val store = source("BatteryStore")
        assertTrue(store.contains("writeReading(r, onlyIfNew = false)"))
        assertTrue(store.contains("writeReading(r, onlyIfNew = true)"))
        val write = store.substringAfter("private fun writeReading(").substringBefore("private fun values(")
        val begin = write.indexOf("db.beginTransaction()")
        val check = write.indexOf("has(r.device, r.source, r.timeMs)")
        val assess = write.indexOf("QualityFlags.assess(latestFrom(r.device, r.source), r)")
        val insert = write.indexOf("db.insertOrThrow(")
        val success = write.indexOf("db.setTransactionSuccessful()")
        assertTrue(begin >= 0 && check > begin && assess > check && insert > assess && success > insert)
        assertTrue(Regex("finally\\s*\\{\\s*db.endTransaction\\(\\)").containsMatchIn(write))
    }

    @Test fun importRefreshesUnderTheInitialAndEveryChunkWriteLock() {
        val restore = source("BatteryStore").substringAfter("fun importCsv(").substringBefore("/** Quoted CSV")
        assertTrue(restore.contains("WHERE id > ?"))
        assertTrue(restore.contains("existing.watermark.toString()"))
        assertTrue(restore.contains("existing.includeCommitted("))
        assertTrue(restore.contains("existing.add(device, source, time)"))
        assertTrue(Regex("db.beginTransaction\\(\\)\\s*try\\s*\\{\\s*refreshReadings\\(\\)").containsMatchIn(restore))
        assertTrue(Regex("db.endTransaction\\(\\)\\s*afterChunkCommit\\(\\)\\s*db.beginTransaction\\(\\)\\s*refreshReadings\\(\\)").containsMatchIn(restore))
    }
}
