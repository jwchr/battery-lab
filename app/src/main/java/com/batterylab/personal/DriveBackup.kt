package com.batterylab.personal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Automatic backup of the full history as a gzipped CSV (about a tenth of the plain size) to a file the user picked once with Android's
 * "Save to" screen (for example in Google Drive). No Google account access or API keys: the
 * document provider (the Drive app) uploads the file. The poll job calls [maybeBackup], which
 * rewrites the file at most every [INTERVAL_MS].
 *
 * Folder mode (preferred): the user picks a folder instead, and each backup is a new dated file.
 * The older copies are pruned only after the new one is written and checked, keeping the newest
 * [KEEP], so a failed upload can never destroy the last good backup. Single-file mode stays for
 * setups made before folders were offered; it overwrites in place.
 */
object DriveBackup {
    private const val KEY_URI = "backup_uri"
    private const val KEY_TREE = "backup_tree_uri"
    const val KEEP = 2
    private val NAME = Regex("battery-lab-\\d{8}-\\d{6}\\.csv\\.gz")

    /** Dated backup files to delete so the newest [keep] remain; anything not named by us is never touched. */
    fun toPrune(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { NAME.matches(it) }.sortedDescending().drop(keep)

    fun fileName(nowMs: Long): String = "battery-lab-" +
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(nowMs)) + ".csv.gz"

    fun treeUri(context: Context): Uri? = PollScheduler.prefs(context).getString(KEY_TREE, null)?.let(Uri::parse)
    fun isOn(context: Context) = uri(context) != null || treeUri(context) != null

    /** Switches to folder mode, dropping any single-file setup. */
    fun enableFolder(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // Re-picking the same folder must not release the grant just taken.
        disable(context, keep = tree)
        PollScheduler.prefs(context).edit().putString(KEY_TREE, tree.toString()).apply()
    }
    private const val KEY_LAST = "backup_last_ms"
    private const val KEY_TRY = "backup_try_ms"
    private const val KEY_FAILS = "backup_fails"
    /**
     * After a failed try, wait 1 h, doubling with each failure up to [INTERVAL_MS]: a revoked or
     * missing file would otherwise cost a full export every 15 minutes.
     */
    private const val RETRY_MS = 3_600_000L
    const val INTERVAL_MS = 12 * 3_600_000L

    fun uri(context: Context): Uri? = PollScheduler.prefs(context).getString(KEY_URI, null)?.let(Uri::parse)
    fun lastBackupMs(context: Context): Long? = PollScheduler.prefs(context).getLong(KEY_LAST, 0L).takeIf { it > 0 }

    fun enable(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        PollScheduler.prefs(context).edit().putString(KEY_URI, uri.toString()).remove(KEY_LAST).remove(KEY_TRY).remove(KEY_FAILS).apply()
    }

    /** Turns backup off and releases the held grants, except [keep]. */
    fun disable(context: Context, keep: Uri? = null) {
        val held = listOfNotNull(uri(context), treeUri(context)).filter { it != keep }
        PollScheduler.prefs(context).edit().remove(KEY_URI).remove(KEY_TREE).remove(KEY_LAST).remove(KEY_TRY).remove(KEY_FAILS).apply()
        for (uri in held) try {
            context.contentResolver.releasePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { }
    }

    /** Writes [staged] as a new dated file in the folder, checks its size, then prunes older copies. */
    private fun writeToFolder(context: Context, tree: Uri, staged: java.io.File, nowMs: Long) {
        val resolver = context.contentResolver
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, dir, "application/gzip", fileName(nowMs))
            ?: error("provider could not create the file")
        try {
            (resolver.openOutputStream(doc, "w") ?: error("provider returned no stream")).use { o ->
                staged.inputStream().use { it.copyTo(o) }
            }
            val size = resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            check(size == null || size == staged.length()) { "size mismatch" }
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(resolver, doc) } catch (_: Exception) { }
            throw e
        }
        // Only now, with the new copy in place, remove the older ones.
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val byName = mutableMapOf<String, String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) byName[c.getString(1) ?: continue] = c.getString(0)
        }
        for (name in toPrune(byName.keys.toList())) try {
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, byName.getValue(name)))
        } catch (_: Exception) { }
    }

    /**
     * Writes the backup if one is set up and it is due (or [force]). Returns true when written.
     * Call from a background thread. The archive is built and checked in a local file first, so
     * the copy in the chosen file is only replaced by a complete, readable one.
     */
    @Synchronized
    fun maybeBackup(context: Context, store: BatteryStore, force: Boolean = false): Boolean {
        val tree = treeUri(context)
        val uri = tree ?: uri(context) ?: return false
        val last = lastBackupMs(context) ?: 0L
        val now = System.currentTimeMillis()
        if (!force && now - last < INTERVAL_MS) return false
        val prefs = PollScheduler.prefs(context)
        val fails = prefs.getInt(KEY_FAILS, 0)
        val wait = if (fails == 0) 0L else minOf(INTERVAL_MS, RETRY_MS shl (fails - 1).coerceAtMost(4))
        if (!force && now - prefs.getLong(KEY_TRY, 0L) in 0 until wait) return false
        prefs.edit().putLong(KEY_TRY, now).apply()
        // Cheap check before the full export: the chosen file must still grant write access.
        if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isWritePermission }) {
            prefs.edit().putInt(KEY_FAILS, fails + 1).apply()
            store.logProbe("backup", "Backup skipped: no write access to the chosen file")
            return false
        }
        val staged = java.io.File(context.cacheDir, "backup-staging.csv.gz")
        return try {
            BackupStreams.writeGzip(staged.outputStream(), store::exportCsv)
            // Read it back: a truncated or corrupt archive never reaches the backup file.
            val lines = BackupStreams.readGzip(staged.inputStream()) { it.lineSequence().count() }
            check(lines >= 1) { "empty archive" }
            if (tree != null) writeToFolder(context, tree, staged, now)
            else {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("provider returned no stream")
                out.use { o -> staged.inputStream().use { it.copyTo(o) } }
            }
            prefs.edit().putLong(KEY_LAST, now).remove(KEY_FAILS).apply()
            true
        } catch (e: Exception) {
            prefs.edit().putInt(KEY_FAILS, fails + 1).apply()
            store.logProbe("backup", "Backup failed: ${e.javaClass.simpleName}")
            false
        } finally {
            staged.delete()
        }
    }
}
