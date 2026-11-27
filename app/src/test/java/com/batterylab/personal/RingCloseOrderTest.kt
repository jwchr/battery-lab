package com.batterylab.personal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neon deep review debate, Nov 26, 2026 (3.P2e-1): the GATT callback runs on a Binder thread,
 * so the close for a Ring-side disconnect must be posted to main only after the state handling
 * has marked the read finished. Posted first, it can run too early and leave the link waiting
 * out the 2 s closer before the one-read lock is released.
 *
 * BluetoothGatt can't be built in a plain JVM test, so this pins the source order instead.
 */
class RingCloseOrderTest {
    private val path = "src/main/java/com/batterylab/personal/RingReader.kt"

    // Gradle runs unit tests from the module folder; also work when run from the repo root.
    private fun source() = listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    @Test fun disconnectCloseIsPostedAfterTheReadIsMarkedFinished() {
        val src = source()
        val body = src.substringAfter("override fun onConnectionStateChange(").substringBefore("private fun handleState(")
        val handled = body.indexOf("handleState(g, status, newState, down)")
        val posted = body.indexOf("if (down) main.post { if (finished.get()) closeGatt() }")
        assertTrue("state handling must run in the callback", handled >= 0)
        assertTrue("close must be posted after state handling", posted > handled)
    }

    @Test fun disconnectBranchMarksTheReadFinished() {
        // complete() sets finished; without it the close posted afterwards would skip closeGatt().
        val state = source().substringAfter("private fun handleState(").substringBefore("override fun onServicesDiscovered(")
        val branch = Regex("""else if \(down\) \{\s*complete\(null, "Ring disconnected before battery reply"\)""")
        assertTrue("disconnect must complete the read", branch.containsMatchIn(state))
    }
}
