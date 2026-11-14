package com.batterylab.personal

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class WatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchReporter.schedule(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(25, 20, 25, 20)
            setBackgroundColor(Color.rgb(11, 20, 33))
        }
        val label = TextView(this).apply {
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "Battery Lab\nWatch ${WatchReporter.batteryPercent(this@WatchActivity) ?: "?"}%"
        }
        val button = Button(this).apply {
            text = "Send reading"
            setOnClickListener {
                WatchReporter.send(this@WatchActivity) { ok ->
                    runOnUiThread {
                        Toast.makeText(this@WatchActivity,
                            if (ok) "Reading queued for phone" else "Could not send reading",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        layout.addView(label)
        layout.addView(button)
        setContentView(layout)
    }
}
