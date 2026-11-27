package com.batterylab.personal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neon deep review debate, Nov 26, 2026 (3.P2e-1): the GATT callback runs on a Binder thread,
 * so the close for a Ring-side disconnect must be posted to main only after the state handling
 * has marked the read finished. Posted first, it can run too early and leave the link waiting
 * out the 2 s closer before the one-read lock is released.
 */
class RingCloseOrderTest {
    @Test fun disconnectCloseIsPostedAfterTheReadIsMarkedFinished() {
        val src = File("src/main/java/com/batterylab/personal/RingReader.kt").readText()
        val body = src.substringAfter("override fun onConnectionStateChange(").substringBefore("private fun handleState(")
        val handled = body.indexOf("handleState(g, status, newState, down)")
        val posted = body.indexOf("if (down) main.post { if (finished.get()) closeGatt() }")
        assertTrue("state handling must run in the callback", handled >= 0)
        assertTrue("close must be posted after state handling", posted > handled)
    }
}
