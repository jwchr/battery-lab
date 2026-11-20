package com.batterylab.personal.rf

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.batterylab.personal.Background
import com.batterylab.personal.BatteryStore
import com.batterylab.rf.Clock
import com.batterylab.rf.Keys
import com.batterylab.rf.ProbeText
import com.batterylab.rf.Scope
import com.batterylab.rf.SessionController
import com.batterylab.rf.SessionHooks
import com.batterylab.rf.SessionStore
import com.batterylab.rf.ThreadScheduler
import java.io.File

/** Timestamps for RF Lab rows: nanoseconds since boot, and wall-clock milliseconds. */
object AndroidClock : Clock {
    override fun bootNs() = SystemClock.elapsedRealtimeNanos()
    override fun wallMs() = System.currentTimeMillis()
}

/**
 * The one session controller in this process. It lives outside the Activity, so rotation does
 * not end a session. Sessions are folders under files/rf/, which no backup rule includes.
 */
object RfRuntime {
    @Volatile private var instance: SessionController? = null

    fun controller(context: Context): SessionController = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    private fun build(app: Context): SessionController = SessionController(
        store = SessionStore(File(app.filesDir, "rf")),
        // Each session gets its own timer thread, shut down at stop (no timers outside a session).
        schedulers = { ThreadScheduler(AndroidClock) },
        meta = { meta(app) },
        hooks = ProbeHooks(app),
    )

    private fun meta(app: Context): Map<String, String> {
        val bootCount = try {
            android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT).toString()
        } catch (_: Exception) { "" }
        val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        return mapOf(Keys.BOOT_COUNT to bootCount, Keys.APP_VERSION to version,
            Keys.PHONE_MODEL to Build.MODEL, Keys.OS_BUILD to Build.DISPLAY)
    }

    /** R18: the only database writes from RF Lab, one probe line at start and one at end. */
    private class ProbeHooks(private val app: Context) : SessionHooks {
        override fun started(scope: Scope, plannedS: Int) = Background.io.execute {
            try { BatteryStore.get(app).logProbe("rf", ProbeText.start(scope, plannedS)) } catch (_: Exception) { }
        }
        override fun ended(scope: Scope, actualS: Int) = Background.io.execute {
            try { BatteryStore.get(app).logProbe("rf", ProbeText.end(scope, actualS)) } catch (_: Exception) { }
        }
    }
}
