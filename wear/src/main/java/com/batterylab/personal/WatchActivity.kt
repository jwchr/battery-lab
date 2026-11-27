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
    private lateinit var label: TextView

    /** The percent shown is read fresh on open, on return, and on each send (audit #4 B4). */
    private fun showPercent() {
        label.text = "Battery Lab\nWatch ${WatchReporter.batteryPercent(this) ?: "?"}%"
    }

    override fun onResume() {
        super.onResume()
        showPercent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchReporter.schedule(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(25, 20, 25, 20)
            setBackgroundColor(Color.rgb(11, 20, 33))
        }
        label = TextView(this).apply {
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        showPercent()
        val button = Button(this).apply {
            text = "Send reading"
            setOnClickListener {
                showPercent()
                WatchReporter.send(this@WatchActivity, urgent = true) { ok ->
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
