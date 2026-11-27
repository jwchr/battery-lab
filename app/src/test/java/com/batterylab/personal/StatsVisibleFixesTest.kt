package com.batterylab.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Owner-approved user-visible fixes from the Neon deep review of Nov 26, 2026. All fixtures are synthetic. */
class StatsVisibleFixesTest {
    private val m = 60_000L
    private val t0 = 1_000_000_000_000L
    private fun r(min: Long, pct: Int, charging: Boolean? = false) = Reading("phone", t0 + min * m, pct, charging, "android")
    private val hourOf = { ms: Long -> (((ms - t0) / (60 * m)) % 24).toInt() }

    /** 1.P2a-ii: 45 -> drained to 40 by the first charging reading -> charged to 44 used to record no charge. */
    @Test fun aShortChargeThatEndsBelowThePrePlugReadingIsKept() {
        val s = BatteryStats.device(listOf(r(0, 45), r(15, 40, true), r(30, 42, true), r(45, 44, true), r(60, 44)), hourOf)
        assertEquals(1, s.charges.size)
        val c = s.charges[0]
        assertEquals(40, c.fromPct)
        assertEquals(44, c.toPct)
        assertEquals("starts at the lower, charging reading", t0 + 15 * m, c.startMs)
    }

    @Test fun aChargeFromTheUnpluggedReadingStillStartsThere() {
        // The first charging reading is higher, so the plug-in level is the reading before it, as before.
        val s = BatteryStats.device(listOf(r(0, 40), r(15, 41, true), r(30, 60, true), r(45, 60)), hourOf)
        assertEquals(40, s.charges.single().fromPct)
        assertEquals(t0, s.charges.single().startMs)
    }

    private fun onBattery(now: Int?, avg: Int?) =
        Reading("phone", t0, 50, false, "android", detail = ReadingDetail(currentNowRaw = now, currentAvgRaw = avg, plugged = 0))

    /** 1.P2o-i: current_now's sign was judged from current_avg whenever that was filled in. */
    @Test fun eachCurrentFieldGetsItsOwnSign() {
        val phone = List(6) { onBattery(now = 300_000, avg = -300_000) }
        assertEquals("Positive while discharging", CapabilityProbe.signConvention(phone) { it.detail?.currentNowRaw })
        assertEquals("Negative while discharging", CapabilityProbe.signConvention(phone) { it.detail?.currentAvgRaw })
    }

    @Test fun aFieldThePhoneNeverFillsHasNoSign() {
        val phone = List(6) { onBattery(now = -300_000, avg = null) }
        assertEquals("Negative while discharging", CapabilityProbe.signConvention(phone) { it.detail?.currentNowRaw })
        assertNull(CapabilityProbe.signConvention(phone) { it.detail?.currentAvgRaw })
    }

    @Test fun inventoryNotesUseEachFieldsOwnSign() {
        val src = listOf("src/main/java/com/batterylab/personal/LabEngine.kt", "app/src/main/java/com/batterylab/personal/LabEngine.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        assertFalse("one shared sign for both rows is gone", src.contains("val sign = signConvention(phone)"))
    }
}
