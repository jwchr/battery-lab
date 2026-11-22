package com.batterylab.personal.rf

import com.batterylab.rf.Keys
import com.batterylab.rf.Provenance
import com.batterylab.rf.Scope
import com.batterylab.rf.SessionReader
import com.batterylab.rf.SessionStore
import com.batterylab.rf.StopReason

/** Read only. Uses metadata and the three standard marker times, never signal observations. */
object GuideHistory {
    fun read(store: SessionStore, sinceMs: Long, dayStartMs: Long, dayEndMs: Long): GuideSteps.HistoryLoaded {
        val sessions = mutableListOf<GuideSteps.SessionFinished>()
        var today = 0
        for (id in store.ids()) {
            try {
                val info = SessionReader.read(store.dir(id))
                if (info.provenance != Provenance.LIVE || info.imported) continue
                val start = info.startWallMs ?: continue
                if (start >= dayStartMs && start < dayEndMs) today++
                if (start < sinceMs || info.state != Keys.STATE_COMPLETE) continue
                val scope = info.scope ?: continue
                val duration = info.durationNs?.takeIf { it >= 0 }?.div(1_000_000_000L) ?: continue
                val planned = info.props[Keys.DURATION_LIMIT_S]?.toIntOrNull() ?: continue
                val startNs = info.props[Keys.START_BOOT_NS]?.toLongOrNull() ?: continue
                val markerTimes = if (scope == Scope.MY_DEVICES)
                    SessionReader.markers(info.dir).filter { it.label in setOf("near", "moving", "far") }
                        .groupBy { it.label }.mapValues { (_, marks) -> marks.first().tBootNs - startNs }
                    else emptyMap()
                sessions += GuideSteps.SessionFinished(scope, duration, markerTimes.keys, start, planned,
                    // Preserve the old 90-second rule, including its definition of a complete file.
                    complete = planned == 90 || info.stopReason == null || info.stopReason in setOf(StopReason.STOP, StopReason.TIME_LIMIT),
                    markerTimesNs = markerTimes, durationNs = info.durationNs ?: continue)
            } catch (_: Exception) {
                // A removed, interrupted or unreadable session cannot satisfy a guide check.
            }
        }
        return GuideSteps.HistoryLoaded(sessions, today)
    }
}
