package com.batterylab.personal.rf

import com.batterylab.rf.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedSessionsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rowKeepsTheOldValuesAndDurationTruncation() {
        val props = mapOf(Keys.START_WALL_MS to "1700000000000", Keys.START_BOOT_NS to "1000000000",
            Keys.END_BOOT_NS to "3500000000", Keys.PROVENANCE to "SIMULATED", Keys.SCOPE to Scope.MY_LINKS.id,
            Keys.IMPORTED to "true", Keys.STATE to "complete")
        val row = SavedSessions.row("sample", props, 4096)
        assertEquals(1_700_000_000_000L, row.startMs)
        assertEquals("2 s", row.length)
        assertEquals("SIMULATED", row.provenance)
        assertEquals(Scope.MY_LINKS.label, row.scope)
        assertEquals(" · IMPORTED", row.imported)
        assertEquals(4096L, row.bytes)
    }

    @Test fun missingMalformedAndUnfinishedPropertiesKeepFallbacks() {
        val missing = SavedSessions.row("sample", null, 0)
        assertNull(missing.startMs); assertEquals("unreadable", missing.length)
        assertEquals("?", missing.provenance); assertEquals("?", missing.scope); assertEquals("", missing.imported)
        val recording = SavedSessions.row("sample", mapOf(Keys.START_WALL_MS to "invalid", Keys.START_BOOT_NS to "invalid",
            Keys.STATE to "recording", Keys.SCOPE to "unknown", Keys.IMPORTED to "TRUE"), 2)
        assertNull(recording.startMs); assertEquals("recording", recording.length)
        assertEquals("?", recording.scope); assertEquals("", recording.imported)
        assertEquals("unreadable", SavedSessions.row("sample", emptyMap(), 0).length)
    }

    @Test fun loadCountsAllSessionsButInitiallyReadsOnlyNewestTwenty() {
        val store = SessionStore(temp.newFolder())
        assertEquals(SavedSessions.Snapshot(0, emptyList()), SavedSessions.load(store, false))
        val ids = (0..21).map { i ->
            store.newId(1_700_000_000_000L + i).also { id ->
                store.dir(id).mkdirs()
                File(store.dir(id), FileNames.PROPERTIES).writeText("${Keys.STATE}=complete\n")
                File(store.dir(id), "sample.csv").writeText("synthetic\n")
            }
        }
        val snapshot = SavedSessions.load(store, false)
        assertEquals(22, snapshot.count)
        assertEquals(ids.reversed().take(20), snapshot.rows.map { it.id })
        snapshot.rows.forEach { assertEquals(store.sizeOf(it.id), it.bytes); assertEquals("complete", it.length) }
        assertEquals(ids.reversed(), SavedSessions.load(store, true).rows.map { it.id })
        File(store.dir(ids.last()), FileNames.PROPERTIES).delete()
        assertEquals("unreadable", SavedSessions.load(store, false).rows.first().length)
    }

    @Test fun deleteAllKeepsTheActiveSessionAndEveryRecordedByte() {
        val store = SessionStore(temp.newFolder())
        fun session(offset: Long): String = store.newId(1_700_000_000_000L + offset).also { id ->
            store.dir(id).mkdirs()
            File(store.dir(id), FileNames.PROPERTIES).writeText("${Keys.STATE}=recording\n")
            File(store.dir(id), "sample.csv").writeText("synthetic\n")
        }
        val active = session(1)
        session(0); session(2)
        val before = store.dir(active).listFiles()!!.associate { it.name to it.readBytes() }
        assertFalse(store.delete(active, active))
        store.deleteAll(active)
        assertEquals(listOf(active), store.ids())
        assertEquals(before.keys, store.dir(active).listFiles()!!.map { it.name }.toSet())
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, File(store.dir(active), name).readBytes()) }
        assertTrue(store.keysStale(active))
        File(store.dir(active), "sample.csv").appendText("more synthetic\n")
        assertTrue(File(store.dir(active), "sample.csv").readText().endsWith("more synthetic\n"))
    }
}
