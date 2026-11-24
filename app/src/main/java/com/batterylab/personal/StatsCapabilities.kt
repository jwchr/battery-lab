package com.batterylab.personal

/** Presentation only: also correct old inventories without waiting for the next stored probe. */
object StatsCapabilities {
    fun batteryOnly(rows: List<CapabilityRow>): List<CapabilityRow> = rows.mapNotNull { row ->
        when {
            row.deviceKind !in setOf("watch", "ring") -> row
            row.fieldKey == "rssi" -> null // Radio diagnostics belong in RF Lab, not battery stats.
            row.fieldKey in setOf("percent_widget", "percent_wear_os", "percent_ble", "charging") -> row
            else -> row.copy(status = CapabilityProbe.UNSUPPORTED, sourceApi = "—", units = "—",
                lastValueSample = null, observedMinStep = null,
                notes = "Not measured: only battery percent and charging state are available")
        }
    }
}
