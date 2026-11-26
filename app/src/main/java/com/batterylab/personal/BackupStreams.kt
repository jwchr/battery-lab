package com.batterylab.personal

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Reads and writes backup files as text and always closes the opened stream, even when the gzip
 * header throws (a damaged file on read, a full disk on write).
 */
internal object BackupStreams {
    /** Reads [raw] as gzip. */
    fun <T> readGzip(raw: InputStream, block: (BufferedReader) -> T): T =
        raw.use { GZIPInputStream(it).bufferedReader().use(block) }

    /** Reads [raw] as gzip when it starts with the gzip magic bytes, or as plain text otherwise. */
    fun <T> readMaybeGzip(raw: InputStream, block: (BufferedReader) -> T): T = raw.use {
        val input = BufferedInputStream(it).apply { mark(2) }
        val gz = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        (if (gz) GZIPInputStream(input) else input).bufferedReader().use(block)
    }

    /** Writes [raw] as gzip. */
    fun <T> writeGzip(raw: OutputStream, block: (BufferedWriter) -> T): T =
        raw.use { GZIPOutputStream(it).bufferedWriter().use(block) }
}
