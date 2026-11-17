package com.batterylab.personal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

object PhoneBattery {
    /** Whether a charger is connected right now, or null if Android won't say. */
    fun isPlugged(context: Context): Boolean? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it > 0 }

    /** The phone's own battery right now, with its context. [trigger] says what asked for it. */
    fun read(context: Context, trigger: String): Reading? {
        val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val percent = (level.toDouble() / scale * 100).toInt().coerceIn(0, 100)
        val state = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        // Plugged in counts as on the charger even when the status says otherwise: Samsung's
        // battery protection can hold the level (Adaptive mode pauses at 80% overnight), and that
        // hold is not drain.
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL ||
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val rawCharge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        // Int.MIN_VALUE / Long.MIN_VALUE mean "not supported"; a real 0 is kept as 0.
        fun prop(id: Int) = bm.getIntProperty(id).takeIf { it != Int.MIN_VALUE }
        fun extra(name: String) = intent.getIntExtra(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val estimate = AndroidEstimates.read(context)
        val ctx = PhoneContext.read(context)
        val detail = ReadingDetail.clock(context, trigger).copy(
            currentNowRaw = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAvgRaw = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            plugged = extra(BatteryManager.EXTRA_PLUGGED),
            status = state.takeIf { it >= 0 },
            health = extra(BatteryManager.EXTRA_HEALTH),
            levelRaw = level, scale = scale,
            cycleCount = extra("android.os.extra.CYCLE_COUNT")?.takeIf { it >= 0 },
            energyRaw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).takeIf { it != Long.MIN_VALUE },
            androidDischargeMs = estimate.dischargeMs, androidChargeMs = estimate.chargeMs,
            androidPersonalized = estimate.personalized,
            thermalStatus = ctx.thermalStatus, transport = ctx.transport, displayState = ctx.displayState,
            powerSave = ctx.powerSave, deviceIdle = ctx.deviceIdle, thermalHeadroomTenths = ctx.thermalHeadroomTenths,
            // Undocumented but long-standing extras of the battery broadcast; absent means unknown.
            maxChargingUa = extra("max_charging_current")?.takeIf { it >= 0 },
            maxChargingUv = extra("max_charging_voltage")?.takeIf { it >= 0 })
        return Reading("phone", System.currentTimeMillis(), percent, charging, "android",
            intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it in -400..1000 },
            intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            rawCharge.takeIf { it > 0 && it != Int.MAX_VALUE }, detail)
    }
}

/** What the phone was doing at the moment of a read. Each value is null when Android won't say. */
data class PhoneContextSnapshot(
    val transport: String?,
    val displayState: Int?,
    val powerSave: Boolean?,
    val deviceIdle: Boolean?,
    val thermalStatus: Int?,
    val thermalHeadroomTenths: Int?
)

/**
 * One-shot reads of system state, taken only when the app is already awake for a check. Nothing
 * here listens in the background or turns on a radio.
 */
object PhoneContext {
    fun read(context: Context): PhoneContextSnapshot {
        val pm = context.getSystemService(PowerManager::class.java)
        return PhoneContextSnapshot(
            transport = transport(context),
            displayState = safe {
                (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                    ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state
            },
            powerSave = safe { pm?.isPowerSaveMode },
            deviceIdle = safe { pm?.isDeviceIdleMode },
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) safe { pm?.currentThermalStatus } else null,
            // NaN when unsupported or asked twice within a second.
            thermalHeadroomTenths = if (Build.VERSION.SDK_INT >= 30) safe {
                pm?.getThermalHeadroom(10)?.takeIf { !it.isNaN() }?.let { Math.round(it * 10) }
            } else null
        )
    }

    fun transport(context: Context): String? = safe {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return@safe null
        val net = cm.activeNetwork ?: return@safe "none"
        val caps = cm.getNetworkCapabilities(net) ?: return@safe "none"
        val base = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "other"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "other"
            else -> "none"
        }
        base + if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) "+vpn" else ""
    }

    private inline fun <T> safe(block: () -> T?): T? = try { block() } catch (e: Exception) { null }
}

/** Android's own estimates for this phone. Null when Android has none right now. */
data class AndroidEstimate(val dischargeMs: Long?, val chargeMs: Long?, val personalized: Boolean?)

object AndroidEstimates {
    fun read(context: Context): AndroidEstimate {
        val pm = context.getSystemService(PowerManager::class.java)
        val bm = context.getSystemService(BatteryManager::class.java)
        val discharge = if (Build.VERSION.SDK_INT >= 31) try {
            pm?.batteryDischargePrediction?.toMillis()?.takeIf { it > 0 }
        } catch (e: Exception) { null } else null
        val personalized = if (discharge != null && Build.VERSION.SDK_INT >= 31) try {
            pm?.isBatteryDischargePredictionPersonalized
        } catch (e: Exception) { null } else null
        // -1 means "not charging or not known yet".
        val charge = try { bm?.computeChargeTimeRemaining()?.takeIf { it > 0 } } catch (e: Exception) { null }
        return AndroidEstimate(discharge, charge, personalized)
    }
}
