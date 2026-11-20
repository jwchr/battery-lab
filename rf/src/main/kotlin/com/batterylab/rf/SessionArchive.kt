package com.batterylab.rf

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed class ImportResult {
    data class Imported(val id: String) : ImportResult()
    data class Rejected(val reason: String) : ImportResult()
}

/**
 * Export and import of one session as a zip (R16). The app hands in the streams from the system
 * save and open dialogs; there is no share sheet, file provider or storage permission.
 */
object SessionArchive {
    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRIES = 64
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    private class Reject(val reason: String) : Exception(reason)

    /** Writes every allowlisted file of the session, unchanged, as one flat zip. */
    fun export(dir: File, out: OutputStream) {
        val files = dir.listFiles()?.filter { it.isFile && FileNames.allowed(it.name) }?.sortedBy { it.name } ?: emptyList()
        ZipOutputStream(out).use { zip ->
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Copies the zip to a temporary file, checks every rule, unpacks it into a new staging folder,
     * and only then renames that folder into rf/sessions/ under a new id, marked imported. On any
     * failure the temporary file and the staging folder are deleted and one reason is returned.
     */
    fun import(input: InputStream, store: SessionStore, wallMs: Long,
               maxArchiveBytes: Long = MAX_ARCHIVE_BYTES): ImportResult {
        store.stagingDir.mkdirs()
        val tag = System.nanoTime().toString()
        val tmp = File(store.stagingDir, "import-$tag.zip")
        val folder = File(store.stagingDir, "session-$tag")
        try {
            copyLimited(input, tmp, maxArchiveBytes)
            unpack(tmp, folder)
            if (!File(folder, FileNames.PROPERTIES).isFile) throw Reject("The file has no session.properties")
            try { SessionReader.read(folder) } catch (e: RfFormatException) { throw Reject(e.message ?: "The session does not parse") }
            val id = store.newId(wallMs)
            updateProperties(folder) {
                it[Keys.ID] = id
                it[Keys.IMPORTED] = "true"
            }
            store.sessionsDir.mkdirs()
            Files.move(folder.toPath(), store.dir(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
            store.enforceRetention(keepId = id)
            return ImportResult.Imported(id)
        } catch (e: Reject) {
            return ImportResult.Rejected(e.reason)
        } catch (e: Exception) {
            return ImportResult.Rejected("The file could not be read (${e.javaClass.simpleName})")
        } finally {
            tmp.delete()
            if (folder.exists()) folder.deleteRecursively()
            store.stagingDir.delete() // only succeeds when empty
        }
    }

    private fun copyLimited(input: InputStream, to: File, max: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        FileOutputStream(to).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw Reject("The file is larger than 64 MB")
                out.write(buf, 0, n)
            }
        }
    }

    private fun unpack(zipFile: File, folder: File) {
        if (!folder.mkdirs()) throw Reject("Could not create the staging folder")
        val seen = HashSet<String>()
        var total = 0L
        val buf = ByteArray(64 * 1024)
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (seen.size + 1 > MAX_ENTRIES) throw Reject("The file has more than $MAX_ENTRIES entries")
                    if (entry.isDirectory) throw Reject("The file contains a folder")
                    if (!FileNames.allowed(name)) throw Reject("The file contains a name RF Lab does not accept")
                    if (!seen.add(name)) throw Reject("The file contains the same name twice")
                    // The name is one of the validated forms, so it cannot leave the staging folder.
                    var entryBytes = 0L
                    FileOutputStream(File(folder, name)).use { out ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            entryBytes += n
                            total += n
                            if (entryBytes > MAX_ENTRY_BYTES) throw Reject("An entry is larger than 32 MB")
                            if (total > MAX_TOTAL_BYTES) throw Reject("The entries add up to more than 64 MB")
                            out.write(buf, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Reject) {
            throw e
        } catch (e: java.util.zip.ZipException) {
            throw Reject("The file is not a valid zip")
        } catch (e: IllegalArgumentException) {
            throw Reject("The file is not a valid zip")
        }
        if (seen.isEmpty()) throw Reject("The file is empty")
    }
}
