package com.batterylab.personal.rf

import com.batterylab.rf.FileNames
import com.batterylab.rf.Keys
import com.batterylab.rf.Props
import com.batterylab.rf.Scope
import com.batterylab.rf.SessionStore
import java.io.File

/** File-backed snapshot. Call load on the I/O worker; rows need no further storage access. */
internal object SavedSessions {
    data class Row(val id: String, val startMs: Long?, val length: String, val provenance: String,
                   val scope: String, val imported: String, val bytes: Long)
    data class Snapshot(val count: Int, val rows: List<Row>)

    fun row(id: String, props: Map<String, String>?, bytes: Long): Row {
        val start = props?.get(Keys.START_WALL_MS)?.toLongOrNull()
        val length = props?.let { p ->
            val s = p[Keys.START_BOOT_NS]?.toLongOrNull(); val e = p[Keys.END_BOOT_NS]?.toLongOrNull()
            if (s != null && e != null) "${(e - s) / 1_000_000_000L} s" else null
        } ?: props?.get(Keys.STATE) ?: "unreadable"
        return Row(id, start, length, props?.get(Keys.PROVENANCE) ?: "?",
            Scope.parse(props?.get(Keys.SCOPE))?.label ?: "?",
            if (props?.get(Keys.IMPORTED) == "true") " · IMPORTED" else "", bytes)
    }

    fun load(store: SessionStore, showAll: Boolean): Snapshot {
        val ids = store.ids().reversed()
        return Snapshot(ids.size, (if (showAll) ids else ids.take(20)).map { id ->
            val props = try { Props.parse(File(store.dir(id), FileNames.PROPERTIES).readText()) } catch (_: Exception) { null }
            row(id, props, store.sizeOf(id))
        })
    }
}
