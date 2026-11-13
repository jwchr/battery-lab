package com.batterylab.widgetprobe

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Question this probe answers: when an ordinary app hosts Samsung's Battery widget with
 * the public AppWidgetHost API, can it read the battery percentages the widget displays?
 *
 * It does not touch Bluetooth, does not talk to any wearable, does not request data from
 * Samsung apps, and has no network permission. It only inspects views that Android has
 * already inflated inside this app's own process from the widget's RemoteViews.
 */
class ProbeActivity : Activity() {
    companion object {
        const val TAG = "BatteryLabProbe"
        const val TARGET_PACKAGE = "com.android.settings.intelligence"
        private const val HOST_ID = 0x4C42
        private const val REQ_BIND = 1
        private const val REQ_CONFIGURE = 2
        private const val PREFS = "probe"
        private const val KEY_IDS = "bound_ids"
        private const val KEY_HISTORY = "history"
    }

    private lateinit var manager: AppWidgetManager
    private lateinit var host: ProbeHost
    private lateinit var widgetArea: LinearLayout
    private lateinit var output: TextView
    private val hostViews = mutableMapOf<Int, AppWidgetHostView>()
    private val lastDumps = mutableMapOf<Int, String>()
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = AppWidgetManager.getInstance(this)
        host = ProbeHost(applicationContext, HOST_ID) { view, nullViews -> onWidgetUpdated(view, nullViews) }
        pendingId = savedInstanceState?.getInt("pendingId", pendingId) ?: pendingId

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            setBackgroundColor(Color.rgb(14, 24, 40))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(label("Battery Lab · Widget probe", 24f, true))
        root.addView(label("Hosts Samsung's Battery widget and records only the text it displays. " +
            "No Bluetooth, no wearable requests, no network."))

        val providers = batteryProviders()
        if (providers.isEmpty()) {
            root.addView(label("No home-screen widgets found from $TARGET_PACKAGE.", emphasis = true))
        }
        for (info in providers) {
            root.addView(button("Add ${info.loadLabel(packageManager)} (${info.provider.shortClassName.substringAfterLast('.')})") {
                bind(info)
            })
        }
        root.addView(button("Dump visible widget text now") { dumpAll("manual") })
        root.addView(button("Share probe report") { share() })
        root.addView(button("Remove hosted widgets") { confirmRemoveAll() })

        widgetArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(widgetArea)
        output = label("", 12f).apply { setTextIsSelectable(true) }
        root.addView(output)

        for (id in savedIds()) {
            if (manager.getAppWidgetInfo(id) == null) forgetId(id) else addHostView(id)
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("pendingId", pendingId)
    }

    override fun onStart() {
        super.onStart()
        // Android re-delivers the latest cached RemoteViews to each hosted widget here.
        try { host.startListening() } catch (e: Exception) { note("startListening failed: $e") }
    }

    override fun onStop() {
        try { host.stopListening() } catch (_: Exception) { }
        super.onStop()
    }

    private fun batteryProviders(): List<AppWidgetProviderInfo> =
        manager.getInstalledProvidersForPackage(TARGET_PACKAGE, null)
            .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }
            .sortedBy { it.provider.className }

    private fun bind(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        note("Allocated widget id $id for ${info.provider.flattenToShortString()}")
        if (manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            onBound(id)
            return
        }
        pendingId = id
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_BIND)
        } catch (e: ActivityNotFoundException) {
            note("System bind dialog unavailable: $e")
            host.deleteAppWidgetId(id)
        }
    }

    @Deprecated("Activity result API kept dependency-free on purpose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BIND -> {
                val id = pendingId
                pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
                if (resultCode == RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) onBound(id)
                else {
                    note("Bind declined or failed (result $resultCode)")
                    if (id != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(id)
                }
            }
            REQ_CONFIGURE -> {
                note("Configure activity returned result $resultCode")
                val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
                savedIds().filter { it !in hostViews }.forEach { addHostView(it) }
                if (id > 0 && id !in hostViews) addHostView(id)
            }
        }
    }

    private fun onBound(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info == null) {
            note("Widget $id bound but provider info is null")
            return
        }
        rememberId(id)
        note("Bound widget $id: ${info.provider.flattenToShortString()} configure=${info.configure?.flattenToShortString()}")
        if (info.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) {
                note("Configure activity could not start: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        addHostView(id)
    }

    private fun addHostView(id: Int) {
        val info = manager.getAppWidgetInfo(id) ?: return
        val view = host.createView(this, id, info)
        // Same size the Samsung launcher reports for a 4x2 Battery widget on this phone.
        val size = if (info.provider.className.contains("List")) SizeF(267f, 260f) else SizeF(267f, 173f)
        view.updateAppWidgetSize(Bundle(), listOf(size))
        val frame = FrameLayout(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            addView(view, FrameLayout.LayoutParams(dp(size.width.toInt()), dp(size.height.toInt())))
        }
        widgetArea.addView(frame)
        hostViews[id] = view
        note("Hosting widget $id")
    }

    private fun onWidgetUpdated(view: AppWidgetHostView, nullViews: Boolean) {
        val id = view.appWidgetId
        if (nullViews) note("Widget $id: provider sent null RemoteViews")
        // Dump now and again shortly after, so list items loaded asynchronously are captured.
        view.post { dump(id, view, "update") }
        view.postDelayed({ dump(id, view, "update+1.5s") }, 1500)
    }

    private fun dumpAll(reason: String) {
        if (hostViews.isEmpty()) note("No widgets hosted yet")
        hostViews.forEach { (id, view) -> dump(id, view, reason) }
    }

    private fun dump(id: Int, view: AppWidgetHostView, reason: String) {
        val tree = StringBuilder()
        describe(view, 0, tree)
        val text = tree.toString()
        val percents = Regex("""(\d{1,3})\s?%""").findAll(text).map { it.value }.toList()
        val changed = lastDumps[id] != text
        lastDumps[id] = text
        val stamp = timestamp()
        val summary = "$stamp widget $id ($reason) percents=${percents.joinToString(",").ifEmpty { "none" }}" +
            if (changed) "" else " (unchanged)"
        appendHistory(summary)
        Log.i(TAG, summary)
        if (changed) text.lineSequence().filter { it.isNotBlank() }.forEach { Log.i(TAG, "  $it") }
        render()
    }

    private fun describe(v: View, depth: Int, out: StringBuilder) {
        if (depth > 30) return
        val idName = if (v.id != View.NO_ID) {
            try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { "0x" + Integer.toHexString(v.id) }
        } else ""
        val parts = mutableListOf<String>()
        if (v is TextView && !v.text.isNullOrEmpty()) parts += "text=\"${v.text}\""
        if (!v.contentDescription.isNullOrEmpty()) parts += "desc=\"${v.contentDescription}\""
        if (v is ProgressBar) parts += "progress=${v.progress}/${v.max}"
        when (v.visibility) {
            View.INVISIBLE -> parts += "[invisible]"
            View.GONE -> parts += "[gone]"
        }
        out.append("  ".repeat(depth)).append(v.javaClass.name.substringAfterLast('.'))
        if (idName.isNotEmpty()) out.append('#').append(idName)
        if (parts.isNotEmpty()) out.append(' ').append(parts.joinToString(" "))
        out.append('\n')
        if (v is ViewGroup) for (i in 0 until v.childCount) describe(v.getChildAt(i), depth + 1, out)
    }

    private fun render() {
        val sb = StringBuilder()
        sb.append("UPDATE HISTORY (newest last)\n")
        history().takeLast(40).forEach { sb.append(it).append('\n') }
        lastDumps.forEach { (id, tree) -> sb.append("\nVIEW TREE · widget $id\n").append(tree) }
        output.text = sb.toString()
    }

    private fun share() {
        val report = buildString {
            appendLine("Battery Lab widget probe report")
            appendLine("Generated ${timestamp()}")
            appendLine("Android SDK ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Review before sharing: widget text can include your device names.")
            appendLine()
            append(output.text)
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Battery Lab widget probe")
            putExtra(Intent.EXTRA_TEXT, report)
        }, "Share probe report"))
    }

    private fun confirmRemoveAll() {
        AlertDialog.Builder(this)
            .setMessage("Stop hosting all widgets in this probe? Your home-screen widget is not affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                savedIds().forEach { host.deleteAppWidgetId(it); forgetId(it) }
                hostViews.clear(); lastDumps.clear(); widgetArea.removeAllViews()
                note("Removed all hosted widgets")
            }.show()
    }

    private fun note(message: String) {
        Log.i(TAG, message)
        appendHistory("${timestamp()} $message")
        if (::output.isInitialized) render()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun savedIds(): Set<Int> =
        prefs().getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
    private fun rememberId(id: Int) =
        prefs().edit().putStringSet(KEY_IDS, savedIds().map { it.toString() }.toSet() + id.toString()).apply()
    private fun forgetId(id: Int) =
        prefs().edit().putStringSet(KEY_IDS, savedIds().filter { it != id }.map { it.toString() }.toSet()).apply()
    private fun history(): List<String> =
        prefs().getString(KEY_HISTORY, "").orEmpty().lines().filter { it.isNotBlank() }
    private fun appendHistory(line: String) =
        prefs().edit().putString(KEY_HISTORY, (history() + line).takeLast(300).joinToString("\n")).apply()

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun label(text: String, size: Float = 14f, emphasis: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(if (emphasis) Color.WHITE else Color.rgb(196, 215, 235))
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        setOnClickListener { action() }
    }
}

/** Host that hands out views which report every RemoteViews update they receive. */
class ProbeHost(
    context: Context,
    hostId: Int,
    private val onUpdate: (AppWidgetHostView, Boolean) -> Unit
) : AppWidgetHost(context, hostId) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        ProbeHostView(context, onUpdate)
}

class ProbeHostView(
    context: Context,
    private val onUpdate: (AppWidgetHostView, Boolean) -> Unit
) : AppWidgetHostView(context) {
    override fun updateAppWidget(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
        onUpdate(this, remoteViews == null)
    }
}
