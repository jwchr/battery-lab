package com.batterylab.personal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neon deep review, Nov 26, 2026: a null connectGatt() held the one-read lock for 20 seconds
 * (3.P1b), and one passing 133 or 8 while connecting failed the whole read (3.P3c).
 * BluetoothGatt can't be built in a plain JVM test, so the wiring is pinned in the source.
 */
class RingConnectRetryTest {
    private val path = "src/main/java/com/batterylab/personal/RingReader.kt"
    private fun source() = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
    private val plenty = RingReader.DEADLINE_MS

    @Test fun onlyAPassingConnectFailureIsRetriedOnce() {
        assertTrue(RingReader.shouldRetryConnect(133, "connecting", retried = false, msLeft = plenty))
        assertTrue(RingReader.shouldRetryConnect(8, "connecting", retried = false, msLeft = plenty))
        assertFalse("only one retry per read", RingReader.shouldRetryConnect(133, "connecting", retried = true, msLeft = plenty))
        assertFalse("other statuses fail at once", RingReader.shouldRetryConnect(19, "connecting", retried = false, msLeft = plenty))
        assertFalse("once connected, a drop is not retried",
            RingReader.shouldRetryConnect(8, "discovering services", retried = false, msLeft = plenty))
        assertFalse("the second try never retries", RingReader.shouldRetryConnect(133, "connecting again", retried = false, msLeft = plenty))
    }

    @Test fun aRetryNeedsEnoughOfTheTwentySecondsLeft() {
        val need = RingReader.RETRY_MIN_LEFT_MS + RingReader.RETRY_DELAY_MS
        assertTrue(RingReader.shouldRetryConnect(133, "connecting", false, need))
        assertFalse(RingReader.shouldRetryConnect(133, "connecting", false, need - 1))
        assertTrue(need < RingReader.DEADLINE_MS)
    }

    @Test fun aNullConnectFailsAtOnce() {
        val connect = source().substringAfter("private fun connect(device: BluetoothDevice)").substringBefore("\n    }")
        assertTrue(connect.contains("""if (g == null) complete(null, "Bluetooth is turning off") else gatt = g"""))
    }

    @Test fun theFailedLinkIsClosedBeforeTheSecondTryAndInsideTheSameRead() {
        val src = source()
        val retry = src.substringAfter("if (shouldRetryConnect(").substringBefore("return\n                }")
        val close = retry.indexOf("closeQuietly(g)")
        val later = retry.indexOf("main.postDelayed(reconnect, RETRY_DELAY_MS)")
        assertTrue("old link closed first", close in 0 until later)
        assertTrue("no second try after the read ended", retry.contains("if (!finished.get()) main.postDelayed(reconnect"))
        // The deadline is not restarted, and complete() cancels a pending second try.
        assertTrue(Regex("postDelayed\\(deadline").findAll(src).count() == 1)
        val complete = src.substringAfter("private fun complete(").substringBefore("\n    }")
        assertTrue(complete.contains("main.removeCallbacks(reconnect)"))
        val again = src.substringAfter("private fun connectAgain() {").substringBefore("\n    }")
        assertTrue(again.trimStart().startsWith("if (finished.get()) return"))
    }

    @Test fun lateEventsFromTheFailedLinkAreIgnored() {
        val state = source().substringAfter("private fun handleState(").substringBefore("\n            if (status")
        assertTrue(state.contains("if (finished.get() || reconnecting) return"))
    }
}
