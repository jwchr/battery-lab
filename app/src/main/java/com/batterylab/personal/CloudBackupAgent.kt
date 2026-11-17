package com.batterylab.personal

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
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
 * On a new phone, Android restores the file at install and [onRestoreFinished] imports it; the
 * import skips readings that already exist, so a repeated restore is harmless.
 */
class CloudBackupAgent : BackupAgent() {
    private fun snapshot() = File(File(filesDir, DIR).apply { mkdirs() }, FILE)

    override fun onFullBackup(data: FullBackupDataOutput) {
        val store = BatteryStore.get(this)
        val target = snapshot()
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
        val file = snapshot()
        if (!file.exists()) return
        val store = BatteryStore.get(this)
        try {
            val (readings, experiments) = GZIPInputStream(file.inputStream()).bufferedReader().use(store::importCsv)
            store.logProbe("backup", "Cloud restore imported $readings readings, $experiments experiments")
        } catch (e: Exception) {
            store.logProbe("backup", "Cloud restore failed: ${e.javaClass.simpleName}")
        }
    }

    // Key/value backup is not used; fullBackupOnly in the manifest routes everything through
    // onFullBackup above.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    companion object {
        const val DIR = "cloud"
        const val FILE = "history.csv.gz"
    }
}
