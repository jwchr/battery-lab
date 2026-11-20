package com.batterylab.rf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T9, the :rf half: no Android import and no reference to the app's package. */
class IsolationTest {
    @Test fun rfHasNoAndroidImportAndNoAppReference() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("found the :rf sources", sources.size >= 5)
        for (f in sources) {
            val text = f.readText()
            assertTrue(f.name, Regex("""^\s*import\s+(android|androidx|com\.google\.android)\.""", RegexOption.MULTILINE).find(text) == null)
            assertTrue(f.name, !Regex("""\bandroid\.[a-z]""").containsMatchIn(text))
            assertTrue(f.name, !text.contains("com.batterylab.personal"))
        }
        val build = File("build.gradle.kts").readText()
        assertTrue(!build.contains("project(\":app\")"))
        assertTrue(!Regex("""^\s*implementation\(""", RegexOption.MULTILINE).containsMatchIn(build))
    }
}
