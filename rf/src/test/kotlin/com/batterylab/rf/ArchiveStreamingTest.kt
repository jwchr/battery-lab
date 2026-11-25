package com.batterylab.rf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Export compatibility and bounded-heap checks use only generated, nonidentifying fixtures. */
class ArchiveStreamingTest {
    @Test fun observationsMatchLegacyOutputIncludingQuotedRecordsAndTornTail() {
        val dir = fixture()
        try {
            val header = listOf("seq", "emitter", "value", "extra_future")
            val input = Csv.line(header) + "\n\"\"\n" + listOf(
                listOf("1", "12", "-50", "comma,value"),
                listOf("2", "5", "", "two \"quotes\""),
                listOf("3", "0", "-70", "multi\nline\r\nvalue"),
                listOf("4", "999", "-80", "unknown"),
                listOf("5", "", "", "missing"),
                listOf("6", "000", "-60", "self"),
                listOf("7", " 12 ", "", "padded number"),
                listOf("8", "not-a-number", "", "invalid"),
                listOf("9", "00", "", "self again"),
                listOf("10", " 0 ", "", "padded self")
            ).joinToString("") { Csv.line(it) } + "11,12,-10,unterminated"
            File(dir, "obs_wifi.csv").writeText(input)
            val files = exported(dir)
            val actual = files.getValue("obs_wifi.csv").toString(Charsets.UTF_8)
            assertEquals(legacyRenumber(input, mapOf("12" to "1", "5" to "2")), actual)
            val table = Csv.parse(actual)
            assertEquals(listOf("1", "2", "0", "", "", "000", "1", "", "00", " 0 "),
                table.rows.map { table.cell(it, "emitter") })
            assertEquals("multi\nline\r\nvalue", table.cell(table.rows[2], "extra_future"))
            assertFalse(actual.contains("unterminated"))
            val emitters = Csv.parse(files.getValue(FileNames.EMITTERS).toString(Charsets.UTF_8))
            assertEquals(listOf("1", "2"), emitters.rows.map { emitters.cell(it, "emitter") })
            assertEquals(listOf("9", "10"), emitters.rows.map { emitters.cell(it, "first_boot_ns") })
            assertFalse("key" in emitters.header)
            assertFalse("name" in emitters.header)
            val named = Csv.parse(exported(dir, true).getValue(FileNames.EMITTERS).toString(Charsets.UTF_8))
            assertEquals(listOf("Fixture newest", "Fixture second"), named.rows.map { named.cell(it, "name") })
            assertArrayEquals(byteArrayOf(0, 1, 10, -1), files.getValue("bin_wifi.dat"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun emptyHeaderlessAndUnmappedObservationsKeepLegacySemantics() {
        val dir = fixture()
        try {
            val samples = listOf("", "\n", "emitter,value", "value,extra\r\n\"a,b\",\"x\"\"y\"\r\n",
                "emitter,emitter,value\n12,5,keep\n", "emitter,value\r\n12,\"a\nb\"\r\n5,torn")
            for (input in samples) {
                File(dir, "obs_wifi.csv").writeText(input)
                assertEquals(input, legacyRenumber(input, mapOf("12" to "1", "5" to "2")),
                    exported(dir).getValue("obs_wifi.csv").toString(Charsets.UTF_8))
            }
            File(dir, FileNames.EMITTERS).delete()
            val untouched = "emitter,value\n12,\"torn".toByteArray()
            File(dir, "obs_wifi.csv").writeBytes(untouched)
            assertArrayEquals(untouched, exported(dir).getValue("obs_wifi.csv"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedOutputIsReportedAndOriginalSessionIsUntouched() {
        val dir = fixture()
        try {
            File(dir, "obs_wifi.csv").writeText("seq,emitter,value\n" +
                (1..1000).joinToString("") { "$it,12,fixture-$it\n" })
            val before = dir.filesByName()
            val sink = object : OutputStream() {
                var closed = false
                var written = 0
                override fun write(b: Int) {
                    if (written++ > 200) throw IOException("Synthetic output failure")
                }
                override fun close() { closed = true }
            }
            try {
                // Match the Android export caller: it owns the output resource on failure.
                sink.use { SessionArchive.export(dir, it) }
                throw AssertionError("An incomplete archive was reported as successful")
            } catch (e: IOException) { assertEquals("Synthetic output failure", e.message) }
            assertTrue(sink.closed)
            val after = dir.filesByName()
            assertEquals(before.keys, after.keys)
            before.forEach { (name, bytes) -> assertArrayEquals(bytes, after.getValue(name)) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun largeExportFits32MiBHeapAndMatchesAnIndependentStreamingDigest() = runBoundedChild("large")

    @Test fun largeDuplicateEmitterHistoryFits32MiBHeapAndKeepsOnlyLatestMetadata() = runBoundedChild("large-emitters")

    private fun runBoundedChild(mode: String) {
        val dir = kotlin.io.path.createTempDirectory("rf-export-heap-").toFile()
        try {
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val classpath = listOf(SessionArchive::class.java, ArchiveStreamingTest::class.java,
                kotlin.Unit::class.java, org.junit.Assert::class.java, org.hamcrest.Matcher::class.java)
                .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)
            val log = File(dir, "child.log")
            val process = ProcessBuilder(java, "-Xmx32m", "-cp", classpath,
                ArchiveStreamingTest::class.java.name, mode, dir.absolutePath)
                .redirectErrorStream(true).redirectOutput(log).start()
            val ended = process.waitFor(90, TimeUnit.SECONDS)
            if (!ended) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            assertTrue("Bounded export child timed out: ${log.readText().takeLast(4000)}", ended)
            assertEquals("Bounded export child failed: ${log.readText().takeLast(4000)}", 0, process.exitValue())
            assertTrue(log.readText(), log.readText().contains("rows=600000"))
            assertTrue(log.readText(), log.readText().contains("digest_match=true"))
            println("RF_EXPORT_HEAP " + log.readText().trim())
        } finally { dir.deleteRecursively() }
    }

    companion object {
        private fun fixture(): File {
            val dir = kotlin.io.path.createTempDirectory("rf-export-fixture-").toFile()
            File(dir, FileNames.PROPERTIES).writeText("format=1\nid=fixture\nidentifier_mode=names\n")
            File(dir, FileNames.EMITTERS).writeText("emitter,key,name,first_boot_ns\n" +
                "12,fixture-a,Fixture old,20\n5,fixture-b,Fixture second,10\n12,fixture-a,Fixture newest,9\n")
            File(dir, "bin_wifi.dat").writeBytes(byteArrayOf(0, 1, 10, -1))
            return dir
        }

        private fun exported(dir: File, names: Boolean = false): Map<String, ByteArray> {
            val bytes = ByteArrayOutputStream().also { SessionArchive.export(dir, it, names) }.toByteArray()
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
            }
            return entries
        }

        /** Frozen pre-change transformation, used only for small compatibility fixtures. */
        private fun legacyRenumber(input: String, renumber: Map<String, String>): String {
            val t = Csv.parse(input)
            val col = t.header.indexOf("emitter")
            val sb = StringBuilder(Csv.line(t.header))
            for (r in t.rows) {
                val cells = r.mapIndexed { i, v ->
                    val num = v.trim().toIntOrNull()?.toString()
                    if (i == col && num != "0" && v.isNotEmpty()) renumber[num] ?: "" else v
                }
                sb.append(Csv.line(cells))
            }
            return sb.toString()
        }

        @JvmStatic fun main(args: Array<String>) {
            require(args.size == 2 && args[0] in setOf("large", "large-emitters"))
            val target = File(args[1])
            val dir = fixture()
            try {
                val expected = MessageDigest.getInstance("SHA-256")
                val header = "seq,emitter,payload\n"
                expected.update(header.toByteArray())
                val payload = "x".repeat(80)
                val obs = File(dir, "obs_wifi.csv")
                val input = if (args[0] == "large") {
                    obs.bufferedWriter(Charsets.UTF_8).use { out ->
                        out.write(header)
                        repeat(600_000) { i ->
                            out.write("${i + 1},12,$payload\n")
                            expected.update("${i + 1},1,$payload\n".toByteArray())
                        }
                    }
                    obs
                } else {
                    val emitters = File(dir, FileNames.EMITTERS)
                    emitters.bufferedWriter(Charsets.UTF_8).use { out ->
                        out.write("emitter,key,name,first_boot_ns\n")
                        repeat(599_998) { out.write("12,fixture-a,$payload,20\n") }
                        out.write("12,fixture-a,Fixture newest,9\n5,fixture-b,Fixture second,10\n")
                    }
                    obs.writeText(header + "1,12,known\n2,5,known\n")
                    expected.update("1,1,known\n2,2,known\n".toByteArray())
                    emitters
                }
                require(input.length() > Runtime.getRuntime().maxMemory())
                val result = File(target, "large.zip")
                result.outputStream().use { SessionArchive.export(dir, it) }
                val actual = MessageDigest.getInstance("SHA-256")
                ZipFile(result).use { zip ->
                    val metadata = zip.getInputStream(zip.getEntry(FileNames.EMITTERS)).bufferedReader().use { it.readText() }
                    check(metadata == "emitter,first_boot_ns\n1,9\n2,10\n") { "Latest emitter metadata mismatch" }
                    zip.getInputStream(zip.getEntry("obs_wifi.csv")).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; actual.update(buffer, 0, n) }
                    }
                }
                check(expected.digest().contentEquals(actual.digest())) { "Streaming digest mismatch" }
                println("mode=${args[0]} rows=600000 input_bytes=${input.length()} max_heap_bytes=${Runtime.getRuntime().maxMemory()} digest_match=true")
            } finally { dir.deleteRecursively() }
        }
    }
}
