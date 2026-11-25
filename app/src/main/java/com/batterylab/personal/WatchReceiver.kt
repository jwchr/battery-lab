package com.batterylab.personal

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/** Receives a Watch8 battery snapshot from the separately installed Wear OS companion. */
class WatchReceiver : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val store = BatteryStore.get(this)
        try {
            forEachIsolated(events, onError = { _, error ->
                Log.w("BatteryLab", "Watch event processing failed (${error.javaClass.simpleName})")
            }) { event ->
                // Each sample has its own path (/batterylab/watch/<time>); older watch builds use
                // the single /batterylab/watch item.
                val path = event.dataItem.uri.path ?: return@forEachIsolated
                if (event.type != DataEvent.TYPE_CHANGED ||
                    (path != "/batterylab/watch" && !path.startsWith("/batterylab/watch/"))) return@forEachIsolated
                val data = DataMapItem.fromDataItem(event.dataItem).dataMap
                val pct = data.getInt("percent", -1)
                if (pct !in 0..100) return@forEachIsolated
                val ts = data.getLong("time_ms", 0L)
                if (ts <= 0) return@forEachIsolated
                val model = data.getString("model") ?: "Watch"
                val identifier = "watch:$model"
                if (!store.has(identifier, "wear_os", ts))
                    store.append(Reading(identifier, ts, pct, data.getBoolean("charging"), "wear_os"))
                // Stored: remove the sample so the watch's backlog doesn't grow.
                if (path != "/batterylab/watch") Wearable.getDataClient(this).deleteDataItems(event.dataItem.uri)
            }
        } finally {
            store.close()
        }
    }
}
