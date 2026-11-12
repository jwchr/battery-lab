package com.batterylab.personal

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** Receives a Watch8 battery snapshot from the separately installed Wear OS companion. */
class WatchReceiver : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val store = BatteryStore(this)
        try {
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED ||
                    event.dataItem.uri.path != "/batterylab/watch") continue
                val data = DataMapItem.fromDataItem(event.dataItem).dataMap
                val pct = data.getInt("percent", -1)
                if (pct !in 0..100) continue
                val ts = data.getLong("time_ms", 0L)
                if (ts <= 0) continue
                val model = data.getString("model") ?: "Watch"
                val identifier = "watch:$model"
                val last = store.latest(identifier)
                if (last?.timeMs == ts) continue
                store.append(Reading(identifier, ts, pct, data.getBoolean("charging"), "wear_os"))
            }
        } finally {
            store.close()
        }
    }
}
