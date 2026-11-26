package com.batterylab.personal

internal class JobFinishGate {
    private var closed = false

    @Synchronized fun stop() {
        closed = true
    }

    @Synchronized fun finish(action: () -> Unit): Boolean {
        if (closed) return false
        closed = true
        action()
        return true
    }
}
