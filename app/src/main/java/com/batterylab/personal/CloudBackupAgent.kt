package com.batterylab.personal

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Android's own backup (Google account, end-to-end encrypted with the screen lock on Android 9+).
 *
 * Only a gzipped CSV of the history is backed up, not the raw database: the raw file grows past
 * Android's 25 MB backup cap within months, while the compressed export stays small. Settings stay
 * on the phone: the widget id and Ring choice are tied to this phone, and the hidden-apps list and
 * electricity rate were designed never to leave it. See res/xml/backup_rules*.xml.
 *
 * On a new phone, Android restores the file at install and [onRestoreFinished] marks it for
 * import. The import itself runs later ([importPending], from the background check or app open):
 * Android gives the restore callback only a short time, and an import of years of history killed
 * part way would roll back with nothing retrying it. Until the import succeeds, backups keep the
 * restored file as is, so a nearly empty new database can't overwrite the history in the cloud.
 * The import skips readings that already exist, so a repeated restore is harmless.
 */
class CloudBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        if (File(dir(this), PENDING).exists()) { super.onFullBackup(data); return }
        val store = BatteryStore.get(this)
        val target = File(dir(this), FILE)
        val staged = File(target.parentFile, "$FILE.tmp")
        try {
            GZIPOutputStream(staged.outputStream()).bufferedWriter().use(store::exportCsv)
            // Same check as the Drive backup: only a complete, readable archive replaces the last one.
            check(GZIPInputStream(staged.inputStream()).bufferedReader().useLines { it.count() } >= 1) { "empty archive" }
            if (!staged.renameTo(target)) error("could not replace snapshot")
        } catch (e: Exception) {
            store.logProbe("backup", "Cloud backup snapshot failed: ${e.javaClass.simpleName}")
        } finally {
            staged.delete()
        }
        super.onFullBackup(data)
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        if (File(dir(this), FILE).exists()) File(dir(this), PENDING).writeText("0")
    }

    // Key/value backup is not used; fullBackupOnly in the manifest routes everything through
    // onFullBackup above.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    companion object {
        const val DIR = "cloud"
        const val FILE = "history.csv.gz"
        private const val PENDING = "import.pending"
        private const val MAX_TRIES = 3

        private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

        /** Imports a restored history if one is waiting. Call on [Background.io]. */
        fun importPending(context: Context) {
            val marker = File(dir(context), PENDING)
            if (!marker.exists()) return
            val file = File(dir(context), FILE)
            val store = BatteryStore.get(context)
            val tries = marker.readText().trim().toIntOrNull() ?: 0
            if (!file.exists() || tries >= MAX_TRIES) {
                // Out of tries: keep the file aside for a manual look; the next backup exports the database.
                if (file.exists()) file.renameTo(File(dir(context), "history-unimported.csv.gz"))
                marker.delete()
                if (tries >= MAX_TRIES) store.logProbe("backup", "Cloud restore gave up after $tries tries")
                return
            }
            // Only a failure counts as a try: a process killed mid-import keeps its committed
            // progress and simply carries on next time.
            try {
                val (readings, experiments) = GZIPInputStream(file.inputStream()).bufferedReader().use(store::importCsv)
                marker.delete()
                store.logProbe("backup", "Cloud restore imported $readings readings, $experiments experiments")
            } catch (e: Throwable) {
                marker.writeText((tries + 1).toString())
                store.logProbe("backup", "Cloud restore failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
