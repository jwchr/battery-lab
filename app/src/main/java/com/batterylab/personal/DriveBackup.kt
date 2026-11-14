package com.batterylab.personal

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.zip.GZIPOutputStream

/**
 * Automatic backup of the full history as a gzipped CSV (about a tenth of the plain size) to a file the user picked once with Android's
 * "Save to" screen (for example in Google Drive). No Google account access or API keys: the
 * document provider (the Drive app) uploads the file. The poll job calls [maybeBackup], which
 * rewrites the file at most every [INTERVAL_MS].
 */
object DriveBackup {
    private const val KEY_URI = "backup_uri"
    private const val KEY_LAST = "backup_last_ms"
    const val INTERVAL_MS = 12 * 3_600_000L

    fun uri(context: Context): Uri? = PollScheduler.prefs(context).getString(KEY_URI, null)?.let(Uri::parse)
    fun lastBackupMs(context: Context): Long? = PollScheduler.prefs(context).getLong(KEY_LAST, 0L).takeIf { it > 0 }

    fun enable(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        PollScheduler.prefs(context).edit().putString(KEY_URI, uri.toString()).remove(KEY_LAST).apply()
    }

    fun disable(context: Context) {
        val uri = uri(context)
        PollScheduler.prefs(context).edit().remove(KEY_URI).remove(KEY_LAST).apply()
        if (uri != null) try {
            context.contentResolver.releasePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) { }
    }

    /**
     * Writes the backup if one is set up and it is due (or [force]). Returns true when written.
     * Call from a background thread. The archive is built and checked in a local file first, so
     * the copy in the chosen file is only replaced by a complete, readable one.
     */
    @Synchronized
    fun maybeBackup(context: Context, store: BatteryStore, force: Boolean = false): Boolean {
        val uri = uri(context) ?: return false
        val last = lastBackupMs(context) ?: 0L
        val now = System.currentTimeMillis()
        if (!force && now - last < INTERVAL_MS) return false
        val staged = java.io.File(context.cacheDir, "backup-staging.csv.gz")
        return try {
            GZIPOutputStream(staged.outputStream()).bufferedWriter().use(store::exportCsv)
            // Read it back: a truncated or corrupt archive never reaches the backup file.
            val lines = java.util.zip.GZIPInputStream(staged.inputStream()).bufferedReader().useLines { it.count() }
            check(lines >= 1) { "empty archive" }
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("provider returned no stream")
            out.use { o -> staged.inputStream().use { it.copyTo(o) } }
            PollScheduler.prefs(context).edit().putLong(KEY_LAST, now).apply()
            true
        } catch (e: Exception) {
            store.logProbe("backup", "Backup failed: ${e.javaClass.simpleName}")
            false
        } finally {
            staged.delete()
        }
    }
}
