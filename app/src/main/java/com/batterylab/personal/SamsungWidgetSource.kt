package com.batterylab.personal

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject

/** One device row/circle as displayed by Samsung's Battery widget. */
data class WidgetSlot(
    val index: Int,
    val percent: Int,
    val progress: Int?,
    /** A second icon next to the percentage; on the test phone this was the charging bolt. */
    val chargingIcon: Boolean,
    /** Shape/tone hash of the device icon, used only to remember which slot is which device. */
    val fingerprint: String?,
    val icon: Bitmap?
)

data class WidgetSnapshot(val timeMs: Long, val slots: List<WidgetSlot>)

/**
 * Reads battery percentages that Samsung's own Battery widget already displays.
 *
 * Evidence (Galaxy S26 Ultra, One UI 9.0, Nov 2026): when this app hosts
 * com.android.settings.intelligence/...GridTypeBatteryWidget through the public
 * AppWidgetHost API after the user approves the system bind dialog, Android inflates the
 * widget inside this process and its percentages are ordinary TextView text. Samsung pushes
 * new views when a value changes. Reading them sends nothing to the Ring or Watch.
 *
 * Limits: device names are not displayed by the widget, so slots are identified by their
 * icon after a one-time user labeling step. Values are only as fresh as Samsung's last
 * update; the timestamp recorded is when Battery Lab observed the widget.
 */
object SamsungWidgetSource {
    const val PROVIDER_PACKAGE = "com.android.settings.intelligence"
    val PROVIDER = ComponentName(PROVIDER_PACKAGE,
        "com.samsung.android.settings.intelligence.widget.GridTypeBatteryWidget")
    const val HOST_ID = 0x424C
    const val SOURCE = "samsung_widget"
    const val WATCH_DEVICE = "watch:samsung_widget"

    const val LABEL_RING = "ring"
    const val LABEL_WATCH = "watch"
    const val LABEL_PHONE = "phone"
    const val LABEL_OTHER = "other"

    private const val KEY_WIDGET_ID = "samsung_widget_id"
    private const val KEY_LABELS = "samsung_widget_labels"
    private const val WIDTH_DP = 267f   // 4x2 size the Samsung launcher reports on the test phone
    private const val HEIGHT_DP = 173f
    private const val MATCH_DISTANCE = 64 // of 512 fingerprint bits
    private const val DUPLICATE_WINDOW_MS = 5 * 60_000L
    private val PROGRESS_IDS = setOf("arc_progress", "list_progress_fg")
    private val PERCENT_TEXT = Regex("""^\s*(\d{1,3})\s*%?\s*$""")

    fun host(context: Context) = AppWidgetHost(context.applicationContext, HOST_ID)

    fun providerInfo(context: Context): AppWidgetProviderInfo? =
        AppWidgetManager.getInstance(context)
            .getInstalledProvidersForPackage(PROVIDER_PACKAGE, null)
            .firstOrNull { it.provider == PROVIDER }

    fun widgetId(context: Context): Int? {
        val id = PollScheduler.prefs(context).getInt(KEY_WIDGET_ID, -1)
        if (id <= 0) return null
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)
        return if (info?.provider == PROVIDER) id else null
    }

    /** Called once the user has approved binding [id]. Replaces any earlier binding. */
    fun onBound(context: Context, id: Int) {
        val previous = PollScheduler.prefs(context).getInt(KEY_WIDGET_ID, -1)
        if (previous > 0 && previous != id) host(context).deleteAppWidgetId(previous)
        PollScheduler.prefs(context).edit().putInt(KEY_WIDGET_ID, id).apply()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, WIDTH_DP.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, WIDTH_DP.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, HEIGHT_DP.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, HEIGHT_DP.toInt())
            if (Build.VERSION.SDK_INT >= 31) {
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    arrayListOf(SizeF(WIDTH_DP, HEIGHT_DP)))
            }
        }
        AppWidgetManager.getInstance(context).updateAppWidgetOptions(id, options)
    }

    fun disconnect(context: Context) {
        val id = PollScheduler.prefs(context).getInt(KEY_WIDGET_ID, -1)
        if (id > 0) host(context).deleteAppWidgetId(id)
        PollScheduler.prefs(context).edit().remove(KEY_WIDGET_ID).apply()
    }

    /**
     * Inflates the latest views Android holds for our hosted widget, offscreen.
     * Must run on the main thread. Returns null if no widget is bound.
     */
    fun snapshot(context: Context): WidgetSnapshot? {
        val id = widgetId(context) ?: return null
        val manager = AppWidgetManager.getInstance(context)
        val info = manager.getAppWidgetInfo(id) ?: return null
        val view = host(context).createView(context.applicationContext, id, info)
        val density = context.resources.displayMetrics.density
        val w = (WIDTH_DP * density).toInt()
        val h = (HEIGHT_DP * density).toInt()
        // Layout lets size-specific RemoteViews resolve and adapter rows materialize.
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        return WidgetSnapshot(System.currentTimeMillis(), parse(view))
    }

    /** Stores labeled Ring/Watch slots as readings. Phone slots are never stored (Android is authoritative). */
    fun record(context: Context, store: BatteryStore, snapshot: WidgetSnapshot): List<Reading> {
        val saved = mutableListOf<Reading>()
        for (slot in snapshot.slots) {
            val device = when (labelFor(context, slot.fingerprint)) {
                LABEL_RING -> PollScheduler.ringDeviceId(context)
                LABEL_WATCH -> WATCH_DEVICE
                else -> null
            } ?: continue
            val reading = Reading(device, snapshot.timeMs, slot.percent, slot.chargingIcon, SOURCE)
            val last = store.latest(device)
            if (last != null && last.source == SOURCE && last.percent == reading.percent &&
                last.charging == reading.charging && reading.timeMs - last.timeMs < DUPLICATE_WINDOW_MS) {
                saved += last
                continue
            }
            store.append(reading)
            saved += reading
        }
        return saved
    }

    /** Snapshot + record; logs and swallows failures so callers (jobs) never crash. */
    fun sample(context: Context, store: BatteryStore): List<Reading> = try {
        snapshot(context)?.let { record(context, store, it) }.orEmpty()
    } catch (e: Exception) {
        store.logProbe(SOURCE, "Widget read failed: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

    fun labelFor(context: Context, fingerprint: String?): String? {
        if (fingerprint == null) return null
        val labels = labels(context)
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (key in labels.keys()) {
            val d = distance(fingerprint, key)
            if (d < bestDistance) { bestDistance = d; best = labels.optString(key) }
        }
        return if (bestDistance <= MATCH_DISTANCE) best else null
    }

    fun setLabel(context: Context, fingerprint: String, label: String) {
        val labels = labels(context)
        // Drop near-duplicates so one icon never carries two labels.
        labels.keys().asSequence().toList()
            .filter { distance(it, fingerprint) <= MATCH_DISTANCE }
            .forEach { labels.remove(it) }
        labels.put(fingerprint, label)
        PollScheduler.prefs(context).edit().putString(KEY_LABELS, labels.toString()).apply()
    }

    private fun labels(context: Context): JSONObject = try {
        JSONObject(PollScheduler.prefs(context).getString(KEY_LABELS, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    // ---- view parsing -------------------------------------------------------------------

    private fun parse(root: View): List<WidgetSlot> {
        val bars = mutableListOf<ProgressBar>()
        walk(root) { v ->
            if (v is ProgressBar && v.isShown(root) && entryName(v) in PROGRESS_IDS) bars += v
        }
        return bars.mapIndexedNotNull { index, bar ->
            val slot = slotRoot(bar, bars)
            val texts = mutableListOf<TextView>()
            val images = mutableListOf<ImageView>()
            walk(slot) { v ->
                if (!v.isShown(slot)) return@walk
                if (v is TextView && !v.text.isNullOrBlank()) texts += v
                if (v is ImageView && v.drawable != null) images += v
            }
            val percent = texts.firstNotNullOfOrNull {
                PERCENT_TEXT.find(it.text)?.groupValues?.get(1)?.toIntOrNull()
            }?.takeIf { it in 0..100 } ?: return@mapIndexedNotNull null // empty slot
            val icon = images.firstOrNull()?.drawable
            WidgetSlot(index, percent, bar.progress, images.size >= 2,
                icon?.let(::fingerprint), icon?.let { render(it, 96) })
        }
    }

    /** Largest ancestor of [bar] that contains no other tracked progress bar. */
    private fun slotRoot(bar: View, bars: List<View>): View {
        var node = bar
        while (true) {
            val parent = node.parent as? ViewGroup ?: return node
            if (bars.count { it !== bar && isDescendant(it, parent) }  > 0) return node
            node = parent
        }
    }

    private fun isDescendant(view: View, ancestor: View): Boolean {
        var p: Any? = view
        while (p is View) { if (p === ancestor) return true; p = p.parent }
        return false
    }

    private fun View.isShown(root: View): Boolean {
        var v: Any? = this
        while (v is View) {
            if (v.visibility != View.VISIBLE) return false
            if (v === root) return true
            v = v.parent
        }
        return true
    }

    private fun walk(v: View, action: (View) -> Unit) {
        action(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), action)
    }

    private fun entryName(v: View): String? =
        if (v.id == View.NO_ID) null else try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { null }

    // ---- icon fingerprint ---------------------------------------------------------------

    /** 256-bit alpha mask + 256-bit "lighter than average" mask of the icon at 16x16. */
    internal fun fingerprint(drawable: Drawable): String? {
        val size = 16
        val bmp = render(drawable, size)
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        val opaque = px.map { Color.alpha(it) > 128 }
        if (opaque.none { it }) return null
        val lum = px.map { 0.299 * Color.red(it) + 0.587 * Color.green(it) + 0.114 * Color.blue(it) }
        val mean = lum.filterIndexed { i, _ -> opaque[i] }.average()
        val bits = opaque + lum.mapIndexed { i, l -> opaque[i] && l > mean }
        return bits.chunked(4).joinToString("") { nibble ->
            Integer.toHexString(nibble.foldIndexed(0) { i, acc, b -> if (b) acc or (8 shr i) else acc })
        }
    }

    internal fun distance(a: String, b: String): Int {
        if (a.length != b.length) return Int.MAX_VALUE
        var d = 0
        for (i in a.indices) d += Integer.bitCount(Character.digit(a[i], 16) xor Character.digit(b[i], 16))
        return d
    }

    private fun render(drawable: Drawable, size: Int): Bitmap {
        val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        copy.setBounds(0, 0, size, size)
        copy.draw(Canvas(bmp))
        return bmp
    }
}
