package com.batterylab.personal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchReceiverIsolationTest {
    @Test fun aFailedEventDoesNotBlockLaterEvents() {
        val stored = mutableListOf<Int>()
        val deleted = mutableListOf<Int>()
        val errors = mutableListOf<Pair<Int, Exception>>()
        val failure = IllegalArgumentException("Bad item")

        val failed = forEachIsolated(listOf(1, 2, 3), onError = { item, error ->
            errors += item to error
        }) { item ->
            if (item == 2) throw failure
            stored += item
            deleted += item
        }

        assertEquals(listOf(1, 3), stored)
        assertEquals(listOf(1, 3), deleted)
        assertEquals(listOf(2), errors.map { it.first })
        assertSame(failure, errors.single().second)
        assertEquals(1, failed)
    }

    @Test fun aDeleteFailureDoesNotBlockLaterStorage() {
        val stored = mutableListOf<Int>()
        val deleted = mutableListOf<Int>()
        val errors = mutableListOf<Int>()

        val failed = forEachIsolated(listOf(1, 2, 3), onError = { item, _ ->
            errors += item
        }) { item ->
            stored += item
            if (item == 2) throw IllegalStateException("Delete failed")
            deleted += item
        }

        assertEquals(listOf(1, 2, 3), stored)
        assertEquals(listOf(1, 3), deleted)
        assertEquals(listOf(2), errors)
        assertEquals(1, failed)
    }

    @Test fun anEmptyBatchDoesNothing() {
        val failed = forEachIsolated(emptyList<Int>(), onError = { _, _ ->
            throw AssertionError("No error callback expected")
        }) {
            throw AssertionError("No event expected")
        }
        assertEquals(0, failed)
    }

    @Test fun receiverUsesIsolationAndAlwaysClosesTheStore() {
        val source = File("src/main/java/com/batterylab/personal/WatchReceiver.kt").readText()
            .substringAfter("override fun onDataChanged(")
        assertTrue(source.contains("forEachIsolated(events"))
        assertTrue(Regex("finally\\s*\\{\\s*store\\.close\\(\\)").containsMatchIn(source))
        assertEquals(1, Regex("store\\.close\\(\\)").findAll(source).count())
    }
}
