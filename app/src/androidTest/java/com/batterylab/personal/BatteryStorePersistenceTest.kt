package com.batterylab.personal

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Actual Android SQLite, WAL enabled. Every test uses its own disposable database directory. */
class BatteryStorePersistenceTest {
    companion object { lateinit var context: Context }
    private lateinit var dir: File
    private val stores = mutableListOf<BatteryStore>()
    private val opened = mutableListOf<SQLiteDatabase>()
    private var databaseOpens = 0
    private lateinit var store: BatteryStore

    private fun newStore(allowOpen: Boolean = true): BatteryStore {
        val dbDir = File(dir, "db-${stores.size}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(dbDir, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                openOrCreateDatabase(name, mode, factory, null)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
                databaseOpens++
                check(allowOpen) { "Database access forbidden in this test" }
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler).also { opened += it }
            }
        }
        val constructor = BatteryStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        return constructor.newInstance(isolated).also { stores += it; if (allowOpen) it.writableDatabase }
    }

    @Before fun setup() {
        dir = File(context.cacheDir, "range-persistence-${System.nanoTime()}").apply { mkdirs() }
        store = newStore()
    }

    @After fun cleanup() {
        // BatteryStore.close() intentionally keeps the shared production helper alive; these are
        // private helpers, so close their databases explicitly before deleting the test directory.
        opened.filter { it.isOpen }.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun seed() {
        val db = store.writableDatabase
        db.execSQL("INSERT INTO readings(device,time_ms,percent,source,charging,plugged) VALUES('phone',100,50,'android',0,1)")
        db.execSQL("INSERT INTO readings(device,time_ms,percent,source) VALUES('watch:synthetic',200,50,'wear_os')")
        db.execSQL("INSERT INTO experiments(device,mode,start_ms,start_pct) VALUES('phone','baseline',100,50)")
        db.execSQL("INSERT INTO probe_events(time_ms,device,message) VALUES(100,'phone','synthetic')")
        db.execSQL("INSERT INTO context_journal(time_ms,device,category,title) VALUES(100,'phone','manual','synthetic')")
        db.execSQL("INSERT INTO capability_probes(field_key,device_kind,firmware_build,status,source_api,units,checked_time_ms) VALUES('synthetic','phone','synthetic','unknown','synthetic','count',100)")
        db.execSQL("INSERT INTO forecasts(device,issued_ms,start_pct,lab_rate_pp_per_hr,lab_hours_to_empty,model_version) VALUES('phone',100,50,1,50,'synthetic')")
    }

    /** Fail on any attempted row write, even one that would leave identical values behind. */
    private fun guardWrites() {
        val db = store.writableDatabase
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (table in tables) for (action in listOf("INSERT", "UPDATE", "DELETE"))
            db.execSQL("CREATE TRIGGER guard_${table}_$action BEFORE $action ON \"$table\" BEGIN SELECT RAISE(ABORT, 'analysis must not write'); END")
    }

    /** Every table including sqlite_sequence, all columns including nulls, plus schema/version. */
    private fun snapshot(s: BatteryStore = store): List<String> {
        val db = s.readableDatabase
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return buildList {
            add("version=${db.version}")
            db.rawQuery("SELECT type,name,sql FROM sqlite_master ORDER BY type,name", null).use { c ->
                while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "<null>" else c.getString(it) })
            }
            for (table in tables) {
                val rows = db.rawQuery("SELECT * FROM \"$table\"", null).use { c ->
                    buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") {
                        if (c.isNull(it)) "<null>" else "${c.getType(it)}:${c.getString(it)}"
                    }) }
                }
                add(table); addAll(rows.sorted())
            }
        }
    }

    private fun backup(s: BatteryStore = store) = StringWriter().also(s::exportCsv).toString()
    private fun analysis(bounds: RangeExport.Bounds = RangeExport.Bounds(null, null)): String = StringWriter().also { out ->
        val encoder = RangeExport.Encoder(out, RangeExport.Request(bounds, ZoneId.of("UTC"), false))
        store.rangeExportReadings(bounds, store.rangeExportMaxId(), encoder::write)
    }.toString()
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).bufferedWriter().use { it.write(text) }
    }.toByteArray()

    @Test fun exportAndRepeatedPlainAndGzipRejectionsLeaveEveryTableAndBackupUnchanged() {
        seed()
        // A newer row and the active experiment must survive every rejected attempt.
        store.writableDatabase.execSQL("INSERT INTO readings(device,time_ms,percent,source) VALUES('phone',9999,50,'android')")
        guardWrites()
        val before = snapshot()
        val originalBackup = backup()
        val output = analysis()
        assertEquals(before, snapshot())
        assertEquals(originalBackup, backup())
        repeat(3) {
            for (reader in listOf(StringReader(output), GZIPInputStream(ByteArrayInputStream(gzip(output))).reader())) {
                reader.use {
                    val failure = assertThrows(IllegalArgumentException::class.java) { store.importCsv(it) }
                    assertEquals(RangeExport.RESTORE_ERROR, failure.message)
                }
                assertEquals(before, snapshot())
                assertEquals(originalBackup, backup())
            }
        }
    }

    @Test fun rejectionDoesNotEvenOpenTheDatabase() {
        val output = analysis()
        val unopened = newStore(allowOpen = false)
        val before = databaseOpens
        assertEquals(RangeExport.RESTORE_ERROR,
            assertThrows(IllegalArgumentException::class.java) { unopened.importCsv(StringReader(output)) }.message)
        assertEquals(before, databaseOpens)
    }

    @Test fun rawChargingAndMultipleSourceRowsAreRetainedAndBoundariesMatchSql() {
        seed()
        store.writableDatabase.execSQL("INSERT INTO readings(device,time_ms,percent,source) VALUES('phone',100,50,'samsung_widget')")
        val before = snapshot()
        val rows = mutableListOf<Reading>()
        store.rangeExportReadings(RangeExport.Bounds(100, 200), store.rangeExportMaxId(), rows::add)
        assertEquals(2, rows.size)
        assertEquals(listOf("android", "samsung_widget"), rows.map { it.source })
        assertEquals(false, rows.first().charging) // The UI reader would reinterpret plugged=1.
        assertEquals(before, snapshot())
    }

    @Test fun emptyExactAndMultiplePagesExcludeConcurrentBackdatedAppends() {
        for (count in listOf(0, 499, 500, 501, 1000, 1001)) {
            store.writableDatabase.execSQL("DELETE FROM readings")
            store.writableDatabase.beginTransaction()
            try {
                repeat(count) { i ->
                    store.writableDatabase.execSQL("INSERT INTO readings(device,time_ms,percent,source) VALUES('phone',?,50,'android')", arrayOf(i))
                }
                store.writableDatabase.setTransactionSuccessful()
            } finally { store.writableDatabase.endTransaction() }
            val max = store.rangeExportMaxId()
            var seen = 0
            store.rangeExportReadings(RangeExport.Bounds(null, null), max) {
                seen++
                if (seen == 1) store.writableDatabase.execSQL("INSERT INTO readings(device,time_ms,percent,source) VALUES('phone',0,50,'android')")
            }
            assertEquals(count, seen)
        }
    }

    @Test fun failedWriterLeavesEveryTableAndBackupUntouched() {
        seed()
        guardWrites()
        val before = snapshot(); val originalBackup = backup()
        val failing = object : Writer() {
            var fail = false
            override fun write(cbuf: CharArray, off: Int, len: Int) { if (fail) throw IOException("synthetic output failure") }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val encoder = RangeExport.Encoder(failing, RangeExport.Request(RangeExport.Bounds(null, null), ZoneId.of("UTC"), false))
        failing.fail = true
        assertThrows(IOException::class.java) {
            store.rangeExportReadings(RangeExport.Bounds(null, null), store.rangeExportMaxId(), encoder::write)
        }
        assertEquals(before, snapshot()); assertEquals(originalBackup, backup())
    }

    @Test fun oldAndCurrentBackupsStillImportAndRepeatWithoutDuplicates() {
        seed()
        val target = newStore()
        assertEquals(2 to 1, target.importCsv(StringReader(backup())))
        val before = snapshot(target)
        assertEquals(0 to 0, target.importCsv(StringReader(backup())))
        assertEquals(before, snapshot(target))
        store.writableDatabase.execSQL("UPDATE experiments SET end_ms=200,end_pct=50 WHERE end_ms IS NULL")
        assertEquals(0 to 1, target.importCsv(StringReader(backup())))
        val completed = snapshot(target)
        assertEquals(0 to 0, target.importCsv(StringReader(backup())))
        assertEquals(completed, snapshot(target))
        val old = "kind,device,mode_or_source,timestamp_utc,percentage\n" +
            "reading,phone,android,2024-01-01T00:00:00.000Z,50\n"
        assertEquals(1 to 0, target.importCsv(StringReader(old)))
        val after = snapshot(target)
        assertEquals(0 to 0, target.importCsv(StringReader(old)))
        assertEquals(after, snapshot(target))
    }

    @Test fun interruptedChunkRetryRetainsCommittedRowsAndDoesNotDuplicate() {
        val header = "kind,device,mode_or_source,timestamp_utc,percentage\n"
        val complete = header + (0 until 2501).joinToString("") { i ->
            "reading,phone,android,${RangeExport.utc(i * 1000L)},50\n"
        }
        // Reader fails after more than one committed 2,000-row chunk.
        val cut = header.length + complete.substring(header.length).lineSequence().take(2200).sumOf { it.length + 1 }
        val interrupted = object : Reader() {
            var position = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (position >= cut) throw IOException("synthetic interrupted input")
                val size = minOf(len, cut - position)
                complete.toCharArray(cbuf, off, position, position + size); position += size
                return size
            }
            override fun close() = Unit
        }
        assertThrows(IOException::class.java) { store.importCsv(interrupted) }
        val count = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(2000, count)
        assertEquals(501 to 0, store.importCsv(StringReader(complete)))
        val before = snapshot()
        assertEquals(0 to 0, store.importCsv(StringReader(complete)))
        assertEquals(before, snapshot())
    }

    @Test fun appendIfNewRejectsAnExistingKeyWithoutChangingAnyRow() {
        val reading = Reading("watch:synthetic", 100, 50, false, "wear_os")
        assertTrue(store.appendIfNew(reading))
        val before = snapshot()
        assertFalse(store.appendIfNew(reading.copy(percent = 49)))
        assertEquals(before, snapshot())
        assertTrue(store.appendIfNew(reading.copy(timeMs = 101)))
        assertTrue(store.appendIfNew(reading.copy(source = "samsung_widget")))
        assertTrue(store.appendIfNew(reading.copy(device = "phone")))
        assertEquals(4, store.since("watch:synthetic", 0).size + store.since("phone", 0).size)
        assertTrue(QualityFlags.has(store.latestFrom("watch:synthetic", "wear_os")?.detail?.qualityFlags,
            QualityFlags.UNCHANGED_REPEAT))
    }

    private fun <T> simultaneousWrites(write: (Int) -> T): List<T> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (0..1).map { index -> executor.submit<T> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS)) { "Writer start timed out" }
                write(index)
            } }
            assertTrue("Both writers must be ready", ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue("Writers must exit before database cleanup", executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test fun simultaneousAppendsAssessFlagsAgainstEachOther() {
        repeat(40) { round ->
            val device = "watch:synthetic-$round"
            store.append(Reading(device, 100, 50, false, "wear_os"))
            // Same new timestamp/value: either ordering must flag exactly the second insert.
            simultaneousWrites {
                store.append(Reading(device, 200, 49, false, "wear_os",
                    detail = ReadingDetail(qualityFlags = QualityFlags.AMBIGUOUS_WIDGET_SLOT)))
            }
            val rows = store.since(device, 200)
            assertEquals(2, rows.size)
            assertEquals(1, rows.count { QualityFlags.has(it.detail?.qualityFlags, QualityFlags.UNCHANGED_REPEAT) })
            assertTrue(rows.all { QualityFlags.has(it.detail?.qualityFlags, QualityFlags.AMBIGUOUS_WIDGET_SLOT) })
        }
    }

    @Test fun simultaneousAppendIfNewStoresExactlyOneSample() {
        repeat(40) { round ->
            val reading = Reading("watch:synthetic-$round", 100, 50, false, "wear_os")
            val results = simultaneousWrites { store.appendIfNew(reading) }
            assertEquals(1, results.count { it })
            assertEquals(1, store.since(reading.device, 0).size)
        }
    }

    @Test fun restorePastTheFirstChunkWithAConcurrentWriterKeepsOneCopyOfEachKey() {
        val header = "kind,device,mode_or_source,timestamp_utc,percentage\n"
        val csv = header + (0 until 3000).joinToString("") { i ->
            "reading,phone,android,${RangeExport.utc(i * 1000L)},50\n"
        }
        // Backdated key that restore reaches only after its first 2,000-row commit.
        val late = Reading("phone", 2500 * 1000L, 50, false, "android")
        fun copies() = store.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM readings WHERE device='phone' AND source='android' AND time_ms=?",
            arrayOf(late.timeMs.toString())).use { it.moveToFirst(); it.getInt(0) }
        var writes = 0
        // Another writer saves the key exactly between the chunks, from its own thread, while
        // no restore transaction is open; restore must then see it and skip its own copy.
        val (added, _) = store.importCsv(StringReader(csv)) {
            val writer = Executors.newSingleThreadExecutor()
            try { assertTrue(writer.submit<Boolean> { store.appendIfNew(late) }.get(20, TimeUnit.SECONDS)) }
            finally { writer.shutdownNow() }
            writes++
        }
        assertEquals("One chunk boundary in 3,000 rows", 1, writes)
        assertEquals("Restore skipped the key the other writer saved", 2999, added)
        assertEquals("Concurrent backdated key stored once", 1, copies())
        val total = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals("Each CSV key stored once", 3000, total)
    }

    @Test fun failedAppendRollsBackAndReleasesTheWriteTransaction() {
        store.writableDatabase.execSQL("CREATE TRIGGER reject_reading BEFORE INSERT ON readings BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val reading = Reading("watch:synthetic", 100, 50, false, "wear_os")
        assertThrows(android.database.sqlite.SQLiteException::class.java) { store.append(reading) }
        assertFalse(store.writableDatabase.inTransaction())
        assertThrows(android.database.sqlite.SQLiteException::class.java) { store.appendIfNew(reading) }
        assertFalse(store.writableDatabase.inTransaction())
        store.writableDatabase.execSQL("DROP TRIGGER reject_reading")
        assertTrue(store.appendIfNew(reading))
        assertEquals(1, store.since(reading.device, 0).size)
    }

    @Test fun widgetRepeatAfterBluetoothReadingIsNotInsertedAgain() {
        val prefsName = "synthetic-widget-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefsName, mode)
        }
        try {
            val fingerprint = "0".repeat(128)
            // Prelabel the phone too so this synthetic test never reads the phone battery.
            SamsungWidgetSource.setLabel(isolated, "f".repeat(128), SamsungWidgetSource.LABEL_PHONE)
            SamsungWidgetSource.setLabel(isolated, fingerprint, SamsungWidgetSource.LABEL_RING)
            fun widget(time: Long, percent: Int = 50, charging: Boolean = false) = SamsungWidgetSource.record(isolated, store,
                WidgetSnapshot(time, listOf(WidgetSlot(0, percent, null, charging, fingerprint, null)), elapsedMs = time))
            assertEquals(100L, widget(100).single().timeMs)
            store.append(Reading(BatteryStore.RING_ID, 200, 50, false, "ring_ble"))
            assertEquals(100L, widget(300).single().timeMs)
            assertEquals(2, store.since(BatteryStore.RING_ID, 0).size)
            // Exactly five minutes is outside the duplicate window; changed values still save.
            assertEquals(300_100L, widget(300_100).single().timeMs)
            assertEquals(300_101L, widget(300_101, percent = 49).single().timeMs)
            assertEquals(300_102L, widget(300_102, percent = 49, charging = true).single().timeMs)
            assertEquals(5, store.since(BatteryStore.RING_ID, 0).size)
        } finally { context.deleteSharedPreferences(prefsName) }
    }
}
