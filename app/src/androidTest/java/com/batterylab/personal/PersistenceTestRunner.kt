package com.batterylab.personal

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.junit.runner.JUnitCore

/** Small offline JUnit 4 runner; no AndroidX test service or network dependency. */
class PersistenceTestRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        BatteryStorePersistenceTest.context = targetContext
        val result = JUnitCore.runClasses(BatteryStorePersistenceTest::class.java)
        finish(if (result.wasSuccessful()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\nPersistence: ${result.runCount} tests, ${result.failureCount} failures\n" +
                result.failures.joinToString("\n") { it.trace })
        })
    }
}
