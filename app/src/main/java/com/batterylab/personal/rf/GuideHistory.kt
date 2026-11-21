package com.batterylab.personal.rf

import com.batterylab.rf.Keys
import com.batterylab.rf.Provenance
import com.batterylab.rf.Scope
import com.batterylab.rf.SessionReader
import com.batterylab.rf.SessionStore

/** Read only. Uses metadata and the three standard marker flags, never signal observations. */
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
                val markers = if (scope == Scope.MY_DEVICES && planned == 90 && duration >= 90)
                    SessionReader.markers(info.dir).map { it.label }.filter { it in setOf("near", "moving", "far") }.toSet()
                    else emptySet()
                sessions += GuideSteps.SessionFinished(scope, duration, markers, start, planned)
            } catch (_: Exception) {
                // A removed, interrupted or unreadable session cannot satisfy a guide check.
            }
        }
        return GuideSteps.HistoryLoaded(sessions, today)
    }
}
