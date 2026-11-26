package com.batterylab.personal

internal fun <T> forEachIsolated(
    items: Iterable<T>,
    onError: (T, Exception) -> Unit,
    handle: (T) -> Unit
): Int {
    var failures = 0
    for (item in items) {
        try {
            handle(item)
        } catch (error: Exception) {
            failures++
            onError(item, error)
        }
    }
    return failures
}
