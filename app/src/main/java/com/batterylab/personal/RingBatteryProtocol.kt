package com.batterylab.personal

/** Read-only decoder for the battery response documented in RFC-SGR-001. */
object RingBatteryProtocol {
    /** Sample: 0b 0b 42 06 00 05 3f 07 ff 08 ff -> 63%, not charging. */
    fun parseBatteryReport(data: ByteArray): Pair<Int, Boolean?>? {
        for (offset in 0 until data.size - 4) {
            if ((data[offset].toInt() and 0xff) != 11 ||
                (data[offset + 1].toInt() and 0xff) != 11 ||
                ((data[offset + 2].toInt() and 0x3f) != 2)) continue
            var percent: Int? = null
            var charging: Boolean? = null
            var i = offset + 3
            while (i + 1 < data.size) {
                val key = data[i].toInt() and 0xff
                val value = data[i + 1].toInt() and 0xff
                if (key == 5 && value in 0..100) percent = value
                if (key == 6 && value <= 1) charging = (value == 1)
                i += 2
            }
            if (percent != null) return percent to charging
        }
        return null
    }

    /**
     * Same decode as [parseBatteryReport], keeping every other key/value pair and the packet hex
     * in [RingPacketReport.rawExtra] (for example "k7=255,k8=255;hex=0b0b420600053f07ff08ff").
     */
    fun parseDetailedReport(data: ByteArray): RingPacketReport? {
        val (percent, charging) = parseBatteryReport(data) ?: return null
        val extras = mutableListOf<String>()
        for (offset in 0 until data.size - 4) {
            if ((data[offset].toInt() and 0xff) != 11 || (data[offset + 1].toInt() and 0xff) != 11 ||
                ((data[offset + 2].toInt() and 0x3f) != 2)) continue
            var i = offset + 3
            while (i + 1 < data.size) {
                val key = data[i].toInt() and 0xff
                if (key != 5 && key != 6) extras += "k$key=${data[i + 1].toInt() and 0xff}"
                i += 2
            }
            break
        }
        val hex = data.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return RingPacketReport(percent, charging, (if (extras.isEmpty()) "" else extras.joinToString(",") + ";") + "hex=$hex")
    }
}

data class RingPacketReport(val percent: Int, val charging: Boolean?, val rawExtra: String)
