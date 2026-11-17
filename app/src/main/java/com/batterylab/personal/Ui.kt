package com.batterylab.personal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.random.Random

/** Warm, light palette: cream cards over a drawn autumn landscape, navy type, amber accents. */
object Palette {
    val ink = Color.rgb(30, 53, 80)
    val body = Color.rgb(84, 98, 117)
    // Dark enough for 11–12 sp captions on the cream cards (about 5:1).
    val quiet = Color.rgb(104, 112, 124)
    val card = Color.argb(222, 255, 253, 250)
    val cardStroke = Color.rgb(236, 229, 216)
    val hairline = Color.rgb(234, 228, 218)
    val tint = Color.rgb(234, 240, 247)
    val tintInk = Color.rgb(42, 74, 114)
    val navy = Color.rgb(36, 69, 107)
    val amber = Color.rgb(233, 162, 59)
    val amberSoft = Color.rgb(253, 243, 224)
    val amberInk = Color.rgb(140, 88, 18)
    val green = Color.rgb(116, 166, 88)
    /** Green for small text; [green] itself is too light to read at 11 sp. */
    val greenInk = Color.rgb(60, 120, 40)
    val phone = Color.rgb(91, 143, 214)
    val watch = Color.rgb(116, 166, 88)
    val ring = Color.rgb(233, 162, 59)
    val track = Color.rgb(238, 241, 245)
}

/** Circular battery gauge: soft track, rounded colored arc, percentage in the middle. */
class GaugeView(context: Context, private val percent: Int?, private val arcColor: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7 * d; this.color = Palette.track
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7 * d; strokeCap = Paint.Cap.ROUND; color = arcColor
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Palette.ink; textAlign = Paint.Align.CENTER; textSize = 17 * d
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    init { contentDescription = percent?.let { "$it percent" } ?: "No reading" }

    override fun onDraw(canvas: Canvas) {
        val inset = 6 * d
        val oval = android.graphics.RectF(inset, inset, width - inset, height - inset)
        canvas.drawArc(oval, 0f, 360f, false, track)
        if (percent != null) canvas.drawArc(oval, -90f, 360f * percent / 100f, false, arc)
        // Center the text box itself (not its baseline) in the circle.
        val baseline = height / 2f - (big.ascent() + big.descent()) / 2f
        canvas.drawText(percent?.let { "$it%" } ?: "—", width / 2f, baseline, big)
    }
}

/**
 * Original drawn scenery: a sunrise over a mountain lake. Lavender-to-peach sky, a glowing sun,
 * soft clouds, five ridges fading into haze, and a still lake that mirrors the sky.
 * Rendered once into a bitmap per size, so blurs cost nothing while scrolling.
 */
class LandscapeDrawable(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cache: android.graphics.Bitmap? = null

    override fun draw(canvas: Canvas) {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return
        val bmp = cache?.takeIf { it.width == w && it.height == h }
            ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also {
                paint(Canvas(it), w.toFloat(), h.toFloat()); cache = it
            }
        canvas.drawBitmap(bmp, bounds.left.toFloat(), bounds.top.toFloat(), null)
    }

    private fun paint(c: Canvas, w: Float, h: Float) {
        val horizon = h * 0.60f
        // Sky: soft lavender at the top through rose to warm peach at the horizon.
        paint.shader = LinearGradient(0f, 0f, 0f, horizon, intArrayOf(
            Color.rgb(205, 200, 236), Color.rgb(228, 206, 228), Color.rgb(250, 212, 206),
            Color.rgb(255, 222, 190), Color.rgb(255, 236, 206)),
            floatArrayOf(0f, 0.3f, 0.6f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, horizon, paint)

        // Sun: a bright core inside two soft halos, sitting just above the far ridges.
        val sx = w * 0.68f
        val sy = horizon - h * 0.13f
        paint.shader = android.graphics.RadialGradient(sx, sy, w * 0.75f,
            intArrayOf(Color.argb(170, 255, 236, 200), Color.argb(70, 255, 214, 180), Color.argb(0, 255, 214, 180)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, horizon, paint)
        paint.shader = null
        paint.color = Color.rgb(255, 246, 228)
        c.drawCircle(sx, sy, 34 * density, paint)

        // Clouds: long blurred ovals tinted by the sunrise.
        paint.maskFilter = android.graphics.BlurMaskFilter(18 * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
        cloud(c, w * 0.18f, h * 0.12f, w * 0.34f, 14 * density, Color.argb(150, 255, 240, 246))
        cloud(c, w * 0.30f, h * 0.16f, w * 0.22f, 9 * density, Color.argb(120, 255, 228, 236))
        cloud(c, w * 0.86f, h * 0.24f, w * 0.30f, 12 * density, Color.argb(130, 255, 236, 228))
        cloud(c, w * 0.52f, h * 0.33f, w * 0.40f, 10 * density, Color.argb(110, 255, 226, 214))
        paint.maskFilter = null

        // Ridges, far to near: each lighter at its base (haze) and cooler with distance.
        ridge(c, w, h, horizon - h * 0.10f, h * 0.10f, 1.3f, 11, Color.rgb(196, 182, 222), Color.rgb(232, 206, 220))
        ridge(c, w, h, horizon - h * 0.06f, h * 0.12f, 2.1f, 23, Color.rgb(160, 146, 200), Color.rgb(214, 186, 210))
        ridge(c, w, h, horizon - h * 0.025f, h * 0.10f, 1.7f, 37, Color.rgb(122, 112, 176), Color.rgb(180, 158, 196))
        ridge(c, w, h, horizon, h * 0.07f, 2.6f, 53, Color.rgb(92, 86, 150), Color.rgb(140, 124, 176))

        // Lake: the sky flipped and dimmed, with a warm glow under the sun and fine shimmer lines.
        paint.shader = LinearGradient(0f, horizon, 0f, h, intArrayOf(
            Color.rgb(246, 214, 206), Color.rgb(214, 196, 226), Color.rgb(160, 160, 210), Color.rgb(108, 116, 176)),
            floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, horizon, w, h, paint)
        paint.shader = android.graphics.RadialGradient(sx, horizon, w * 0.35f,
            intArrayOf(Color.argb(150, 255, 230, 200), Color.argb(0, 255, 230, 200)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, horizon, w, h, paint)
        paint.shader = null
        // Mirror of the nearest ridge, faint.
        c.save()
        c.scale(1f, -1f, 0f, horizon)
        ridge(c, w, h, horizon, h * 0.07f, 2.6f, 53, Color.argb(60, 92, 86, 150), Color.argb(0, 140, 124, 176))
        c.restore()
        val rnd = Random(7)
        for (i in 0 until 26) {
            val y = horizon + (h - horizon) * (0.04f + 0.9f * rnd.nextFloat())
            val len = w * (0.04f + 0.16f * rnd.nextFloat()) * (0.5f + (y - horizon) / (h - horizon))
            val x = sx + (rnd.nextFloat() - 0.5f) * w * 0.7f
            paint.color = Color.argb(70 + rnd.nextInt(70), 255, 244, 232)
            c.drawRoundRect(x - len / 2, y, x + len / 2, y + 1.6f * density, density, density, paint)
        }
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, width: Float, height: Float, color: Int) {
        paint.color = color
        c.drawOval(cx - width / 2, cy - height, cx + width / 2, cy + height, paint)
    }

    /** A smooth ridge from summed sine waves; [seed] varies the shape, gradient adds depth haze. */
    private fun ridge(c: Canvas, w: Float, h: Float, base: Float, amp: Float, freq: Float, seed: Int,
                      top: Int, bottom: Int) {
        val path = Path().apply {
            moveTo(0f, base + amp)
            val steps = 80
            for (i in 0..steps) {
                val x = w * i / steps
                val t = x / w * Math.PI * freq
                val y = base - amp * (0.55f * Math.sin(t + seed).toFloat() +
                    0.30f * Math.sin(t * 2.3 + seed * 0.7).toFloat() + 0.15f * Math.sin(t * 5.1 + seed * 1.3).toFloat())
                lineTo(x, y)
            }
            lineTo(w, base + amp)
            close()
        }
        paint.shader = LinearGradient(0f, base - amp, 0f, base + amp, top, bottom, Shader.TileMode.CLAMP)
        c.drawPath(path, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Tiny line drawing of a phone, watch or ring, picked from the device's display name. */
class DeviceIcon(context: android.content.Context, private val name: String, private val tint: Int) : android.view.View(context) {
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; color = tint; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = tint }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val u = minOf(w, h)
        stroke.strokeWidth = u * 0.1f
        val cx = w / 2; val cy = h / 2
        when {
            name.contains("Watch", true) -> {
                canvas.drawRoundRect(cx - u * 0.17f, cy - u * 0.48f, cx + u * 0.17f, cy + u * 0.48f, u * 0.06f, u * 0.06f, fill)
                canvas.drawCircle(cx, cy, u * 0.32f, fill)
                fill.alpha = 0; stroke.alpha = 255
                canvas.drawCircle(cx, cy, u * 0.32f, stroke)
                // Hands, cut out of the face so the face reads as a dial.
                val hand = android.graphics.Paint(stroke).apply { color = Palette.ink; strokeWidth = u * 0.07f; alpha = 140 }
                canvas.drawLine(cx, cy, cx, cy - u * 0.17f, hand)
                canvas.drawLine(cx, cy, cx + u * 0.12f, cy, hand)
                fill.alpha = 255
            }
            name.contains("Ring", true) -> {
                stroke.strokeWidth = u * 0.16f
                canvas.drawOval(cx - u * 0.38f, cy - u * 0.28f, cx + u * 0.38f, cy + u * 0.3f, stroke)
                stroke.strokeWidth = u * 0.07f
                canvas.drawArc(cx - u * 0.38f, cy - u * 0.12f, cx + u * 0.38f, cy + u * 0.44f, 20f, 140f, false, stroke)
            }
            else -> {
                stroke.strokeWidth = u * 0.1f
                canvas.drawRoundRect(cx - u * 0.26f, cy - u * 0.45f, cx + u * 0.26f, cy + u * 0.45f, u * 0.1f, u * 0.1f, stroke)
                canvas.drawCircle(cx, cy - u * 0.32f, u * 0.035f, fill)
            }
        }
    }
}
