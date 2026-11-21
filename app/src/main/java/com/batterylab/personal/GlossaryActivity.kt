package com.batterylab.personal

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/** A read-only guide to Stats. Opening it does not load history or start any collection. */
class GlossaryActivity : StyledActivity() {
    override fun iconFor(eyebrow: String): String? = StatsActivity.SECTION_ICONS[eyebrow]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpPage()
        body.addView(TextView(this).apply {
            text = "←  Back"
            textSize = 14f; typeface = medium; setTextColor(ink)
            setPadding(dp(14), dp(8), dp(16), dp(8))
            background = rounded(Color.argb(215, 255, 253, 248), 14, Palette.cardStroke)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
        body.addView(label("Glossary", 28f, ink, bold = true).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        body.addView(label("What every number in Stats means.", 14f, Palette.body).apply {
            setPadding(dp(4), 0, 0, dp(14))
        })
        // Entries are grouped in the order the Stats screen renders its sections.
        Glossary.entries.groupBy { it.section }.forEach { (title, entries) ->
            section(title).apply {
                entries.forEach { entry ->
                    addView(label(entry.term, 15f, ink, bold = true).apply {
                        setTypeface(medium, Typeface.BOLD)
                        setPadding(0, dp(14), 0, dp(3))
                    })
                    addView(label(entry.meaning, 13f, Palette.body))
                    addView(label("How it's worked out:", 12f, ink, bold = true))
                    addView(label(entry.howWorkedOut, 12.5f, Palette.body))
                }
            }
        }
    }
}
