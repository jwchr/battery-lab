package com.batterylab.personal.rf

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Bluetooth on/off receiver must be exported on API 33+, or it silently misses ACTION_STATE_CHANGED. */
class BluetoothWatchFlagsTest {
    @Test fun exportedFromApi33On() {
        for (sdk in listOf(33, 34, 35, 36, 37)) {
            assertEquals("API $sdk registers the receiver as exported", Context.RECEIVER_EXPORTED, BluetoothWatch.receiverFlags(sdk))
        }
    }

    @Test fun noFlagsBeforeApi33() {
        assertNull(BluetoothWatch.receiverFlags(32))
        assertNull(BluetoothWatch.receiverFlags(26))
    }

    @Test fun startRegistersWithTheCheckedFlags() {
        val src = File("src/main/java/com/batterylab/personal/rf/BluetoothWatch.kt").readText()
        assertTrue("start() passes receiverFlags(...) to registerReceiver",
            src.contains("val flags = receiverFlags(Build.VERSION.SDK_INT)") &&
                src.contains("context.registerReceiver(r, filter, null, handler, flags)"))
        assertTrue("no not-exported registration", !src.contains("RECEIVER_NOT_EXPORTED"))
    }
}
