package com.batterylab.personal

/**
 * Picks where an A/B test's start or finish Ring reading comes from.
 *
 * Only the monitoring arm may wake the Ring over BLE. Every other arm (baseline, ring_alone,
 * ring_with_watch) is meant to add zero Ring wakeups, so when no widget or fresh manual reading
 * exists it refuses instead of silently falling back to a BLE read.
 */
object ExperimentEndpoint {
    /** Whether anything may connect to the Ring over BLE while [activeMode] runs (null: no test). */
    fun bleAllowed(activeMode: String?) = activeMode == null || activeMode == "monitoring"

    /** Whether the monitoring switch may change: never mid-test, since it would change the arm's conditions. */
    fun monitoringSwitchAllowed(activeMode: String?) = activeMode == null

    /** An imported copy completes a known test only if that test is still open and the end is valid. */
    fun importCompletes(knownEndMs: Long?, startMs: Long, endMs: Long?, endPct: Int?) =
        knownEndMs == null && endMs != null && endPct != null && endMs > startMs

    const val MANUAL_MAX_AGE_MS = 120_000L

    sealed class Choice {
        /** Use this stored reading as the endpoint. */
        data class Use(val reading: Reading) : Choice()
        /** Read the Ring over BLE (monitoring arm only). */
        object ReadBle : Choice()
        /** No zero-radio reading is available; the arm must not start or finish now. */
        data class Refuse(val reason: String) : Choice()
    }

    fun allowsBle(mode: String) = mode == "monitoring"

    /**
     * @param widgetRingPercent the percent the Samsung widget shows for the labeled Ring slot right
     *   now, or null when the widget is unreadable or has no Ring slot.
     * @param latest the newest stored reading for the Ring device. Widget readings are deduplicated,
     *   so its time is when that value was first seen, which can be well before [nowMs].
     */
    fun choose(
        mode: String,
        widgetRingPercent: Int?,
        latest: Reading?,
        nowMs: Long,
        hasRingAddress: Boolean,
        widgetSource: String = SamsungWidgetSource.SOURCE,
    ): Choice {
        if (widgetRingPercent != null && latest != null && latest.source == widgetSource &&
            latest.percent == widgetRingPercent) return Choice.Use(latest)
        if (latest != null && latest.source == "manual_widget" && nowMs - latest.timeMs in 0..MANUAL_MAX_AGE_MS)
            return Choice.Use(latest)
        if (allowsBle(mode) && hasRingAddress) return Choice.ReadBle
        return Choice.Refuse(
            if (allowsBle(mode)) "No current Ring reading. Label the Ring in the Samsung widget section, enter it manually, or choose your paired Ring."
            else "The Samsung widget isn't showing the Ring right now, and this test must not wake the Ring. " +
                "Try again once the Ring shows in the widget, or enter its percent manually."
        )
    }
}
