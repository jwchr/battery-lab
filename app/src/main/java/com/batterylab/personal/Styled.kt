package com.batterylab.personal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Shared look for Battery Lab screens: scenery behind a scrolling column of cream cards, plus the
 * card, label, button, chip and bar helpers. Subclasses call [setUpPage] in onCreate.
 */
abstract class StyledActivity : Activity() {
    protected lateinit var body: LinearLayout
    protected lateinit var scroll: ScrollView
    protected val ink = Palette.ink
    protected val quiet = Palette.body
    protected val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    protected val pageReady: Boolean get() = ::body.isInitialized

    protected fun setUpPage() {
        // The page is drawn for light mode only; One UI's forced dark mode would darken the cards
        // and text but not the painted landscape behind them.
        if (android.os.Build.VERSION.SDK_INT >= 29) window.decorView.isForceDarkAllowed = false
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(28))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            // Fixed scenery behind scrolling cards.
            background = LandscapeDrawable(resources.displayMetrics.density)
            addView(body)
            // Scenery runs behind the status bar, but cards are clipped below it so text never
            // scrolls under the clock.
            clipToPadding = true
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                // Sides too: in landscape the navigation buttons (and camera cutout) sit left or right.
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, 0)
                @Suppress("DEPRECATION")
                body.setPadding(dp(16), dp(14), dp(16), insets.systemWindowInsetBottom + dp(28))
                insets
            }
        }
        setContentView(scroll)
        // After setContentView: the window's insets controller needs the decor view to exist.
        drawEdgeToEdge()
    }

    protected fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    @Suppress("DEPRECATION")
    protected fun drawEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    protected fun label(text: String, size: Float = 15f, color: Int = quiet, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(3), 0, dp(5))
            if (bold) typeface = medium
        }

    protected fun eyebrow(text: String, color: Int = Palette.quiet) = TextView(this).apply {
        this.text = text.uppercase(Locale.getDefault())
        textSize = 11f
        letterSpacing = 0.16f
        typeface = medium
        setTextColor(color)
    }

    protected fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    protected fun tag(text: String) = TextView(this).apply {
        this.text = text.uppercase(Locale.getDefault())
        textSize = 9.5f
        letterSpacing = 0.12f
        typeface = medium
        setTextColor(Palette.quiet)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = rounded(Color.TRANSPARENT, 6, Color.rgb(217, 210, 196))
    }

    /** Emoji shown in a section's heading badge; pages override this to decorate their cards. */
    protected open fun iconFor(eyebrow: String): String? = null

    /** A small round badge holding one emoji, used beside section headings. */
    protected fun iconBadge(emoji: String) = TextView(this).apply {
        text = emoji; textSize = 15f; gravity = android.view.Gravity.CENTER
        background = rounded(Color.argb(170, 255, 255, 255), 15, Palette.hairline)
        includeFontPadding = false
    }

    /** Cream card: small spaced eyebrow, friendly headline, optional corner tag. */
    protected fun section(eyebrow: String, title: String? = null, description: String? = null, tag: String? = null,
                        fill: Int = Palette.card, tagView: View? = null): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = rounded(fill, 20, Palette.cardStroke)
            elevation = dp(2).toFloat()
        }
        card.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            iconFor(eyebrow)?.let { addView(iconBadge(it), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) }) }
            addView(eyebrow(eyebrow), LinearLayout.LayoutParams(0, -2, 1f))
            if (tagView != null) addView(tagView) else if (tag != null) addView(tag(tag))
        })
        if (title != null) card.addView(TextView(this).apply {
            text = title
            textSize = 23f
            typeface = medium
            letterSpacing = -0.01f
            setTextColor(ink)
            setPadding(0, dp(6), 0, dp(2))
        })
        if (description != null) card.addView(label(description, 13f))
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return card
    }

    /** Full-width soft button with a trailing arrow; primary is filled navy. */
    protected fun makeButton(text: String, primary: Boolean = false, onClick: () -> Unit): View {
        val fg = if (primary) Color.WHITE else Palette.tintInk
        return LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(14), 0)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(40, 36, 69, 107)),
                rounded(if (primary) Palette.navy else Palette.tint, 10), null)
            isClickable = true
            contentDescription = text
            setOnClickListener { onClick() }
            addView(TextView(this@StyledActivity).apply {
                this.text = text
                textSize = 14f
                typeface = medium
                setTextColor(fg)
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@StyledActivity).apply { this.text = "→"; textSize = 16f; setTextColor(fg) })
        }
    }

    protected fun LinearLayout.button(text: String, primary: Boolean = false, onClick: () -> Unit) {
        addView(makeButton(text, primary, onClick), LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(8)
        })
    }

    protected fun chip(text: String, dot: Int) = TextView(this).apply {
        this.text = "●  $text"
        textSize = 12.5f
        typeface = medium
        setTextColor(ink)
        setPadding(dp(11), dp(6), dp(12), dp(6))
        background = rounded(Color.WHITE, 8, Color.rgb(226, 220, 207))
        val s = android.text.SpannableString(this.text)
        s.setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, 0)
        this.text = s
    }

    /** Full-width device heading: a band in the device's color with a tiny icon and white name. */
    protected fun deviceBanner(name: String, color: Int) = LinearLayout(this).apply {
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(12), dp(6))
        // Darkened so white text reads at about 4.5:1 even on the lightest band (the Ring's amber).
        val deep = android.graphics.Color.rgb(
            (Color.red(color) * 0.66).toInt(), (Color.green(color) * 0.66).toInt(), (Color.blue(color) * 0.66).toInt())
        background = rounded(deep, 9)
        addView(DeviceIcon(this@StyledActivity, name, Color.WHITE), LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(TextView(this@StyledActivity).apply {
            text = name; textSize = 13.5f; typeface = medium; setTextColor(Color.WHITE)
            letterSpacing = 0.01f; setPadding(dp(8), 0, 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(2) }
    }

    protected fun chipRow(vararg chips: View) = LinearLayout(this).apply {
        setPadding(0, dp(10), 0, dp(6))
        chips.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }) }
    }

    /** Two-column grid of stat tiles: big value, small label, optional caption. */
    /** One stat tile: a big value, its label, an optional caption and an optional emoji on top. */
    protected data class Tile(val value: String, val label: String, val caption: String? = null, val icon: String? = null)

    protected fun LinearLayout.addTiles(tiles: List<Triple<String, String, String?>>) =
        addTileList(tiles.map { (v, l, c) -> Tile(v, l, c) })

    protected fun LinearLayout.addTileList(tiles: List<Tile>) {
        tiles.chunked(2).forEach { row ->
            addView(LinearLayout(this@StyledActivity).apply {
                row.forEachIndexed { i, (value, labelText, caption, icon) ->
                    addView(LinearLayout(this@StyledActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(9), dp(10), dp(9))
                        background = rounded(Color.argb(150, 255, 255, 255), 14, Palette.hairline)
                        if (icon != null) addView(TextView(this@StyledActivity).apply {
                            text = icon; textSize = 19f; includeFontPadding = false; setPadding(0, 0, 0, dp(4))
                        })
                        // Value and label share a baseline: "76%  used by apps on screen".
                        // One TextView with two sizes, so a long label wraps cleanly instead of clipping.
                        addView(TextView(this@StyledActivity).apply {
                            val s = android.text.SpannableStringBuilder(value)
                            s.setSpan(android.text.style.AbsoluteSizeSpan(20, true), 0, value.length, 0)
                            s.setSpan(android.text.style.ForegroundColorSpan(ink), 0, value.length, 0)
                            s.setSpan(android.text.style.TypefaceSpan(medium), 0, value.length, 0)
                            s.append("  ").append(labelText)
                            text = s; textSize = 12.5f; setTextColor(Palette.body)
                            setLineSpacing(0f, 0.95f)
                        })
                        if (caption != null) addView(TextView(this@StyledActivity).apply {
                            text = caption; textSize = 11f; setTextColor(Palette.quiet)
                        })
                    }, LinearLayout.LayoutParams(0, if (row.size == 1) -2 else -1, 1f).apply { if (i == 0) rightMargin = dp(8) })
                }
                if (row.size == 1) addView(View(this@StyledActivity), LinearLayout.LayoutParams(0, 0, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    /**
     * Evidence inspector: a small link at the end of a card that explains where its numbers come
     * from, the formula, and what was included or left out.
     */
    protected fun LinearLayout.evidence(source: String, formula: String, coverage: String) {
        addView(TextView(this@StyledActivity).apply {
            text = "\u24d8  How this is worked out"
            textSize = 12f; typeface = medium; setTextColor(Palette.tintInk)
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@StyledActivity).setTitle("How this is worked out")
                    .setMessage("SOURCE\n$source\n\nFORMULA\n$formula\n\nCOVERAGE\n$coverage")
                    .setPositiveButton("OK", null).show()
            }
        })
    }

    protected fun hairline() = View(this).apply { setBackgroundColor(Palette.hairline) }

    protected fun f1(number: Double): String = String.format(Locale.US, "%.1f", number)

    /** Horizontal bar: monospace label, proportional fill, bold value, optional caption below. */
    protected fun barRow(left: String, fraction: Double, value: String, color: Int, caption: String? = null) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(4))
            addView(LinearLayout(this@StyledActivity).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(this@StyledActivity).apply {
                    text = left; textSize = 11.5f; typeface = Typeface.MONOSPACE; setTextColor(Palette.body)
                }, LinearLayout.LayoutParams(dp(78), -2))
                val fill = fraction.coerceIn(0.03, 1.0).toFloat()
                addView(LinearLayout(this@StyledActivity).apply {
                    background = rounded(Palette.track, 4)
                    addView(View(this@StyledActivity).apply { background = rounded(color, 4) },
                        LinearLayout.LayoutParams(0, -1, fill))
                    addView(View(this@StyledActivity), LinearLayout.LayoutParams(0, -1, 1f - fill))
                }, LinearLayout.LayoutParams(0, dp(12), 1f))
                addView(TextView(this@StyledActivity).apply {
                    text = value; textSize = 14f; typeface = medium; setTextColor(ink)
                    gravity = android.view.Gravity.END
                }, LinearLayout.LayoutParams(dp(62), -2))
            })
            if (caption != null) addView(TextView(this@StyledActivity).apply {
                text = caption; textSize = 11.5f; setTextColor(Palette.quiet)
                setPadding(dp(78), dp(2), 0, 0)
            })
        }
}
