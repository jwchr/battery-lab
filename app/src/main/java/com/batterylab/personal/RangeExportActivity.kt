package com.batterylab.personal

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Only reads saved history. No sampling, scheduler, monitoring or radio calls belong here. */
class RangeExportActivity : StyledActivity() {
    private lateinit var range: Spinner
    private lateinit var names: CheckBox
    private lateinit var custom: LinearLayout
    private lateinit var fromButton: Button
    private lateinit var toButton: Button
    private lateinit var exportButton: Button
    private lateinit var status: TextView
    private var from = LocalDate.now()
    private var to = from
    private var job: ExportJob? = null
    private var statusMessage = ""
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        from = savedInstanceState?.getString("from")?.let(LocalDate::parse) ?: from
        to = savedInstanceState?.getString("to")?.let(LocalDate::parse) ?: to
        statusMessage = savedInstanceState?.getString("statusMessage") ?: ""
        var interrupted = false
        job = (lastNonConfigurationInstance as? ExportJob)
            ?: savedInstanceState?.getBundle("job")?.let {
                try { ExportJob.restore(applicationContext, it) }
                catch (e: Exception) { interrupted = true; null }
            }
        setUpPage()
        section("Your data", "Export readings", "Analysis export — not a full backup; cannot restore. For a restorable backup, use the Export full backup button in Your data.").apply {
            addView(label("Range"))
            range = Spinner(this@RangeExportActivity).apply {
                adapter = ArrayAdapter(this@RangeExportActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("Last 24 hours", "Last 7 days", "Last 30 days", "All history", "Custom dates"))
                setSelection(savedInstanceState?.getInt("range") ?: 0)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        custom.visibility = if (position == RangeExport.Preset.CUSTOM.ordinal) View.VISIBLE else View.GONE
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
            addView(range)
            custom = LinearLayout(this@RangeExportActivity).apply { orientation = LinearLayout.VERTICAL }
            fromButton = Button(this@RangeExportActivity).apply { setOnClickListener { pickDate(true) } }
            toButton = Button(this@RangeExportActivity).apply { setOnClickListener { pickDate(false) } }
            custom.addView(fromButton); custom.addView(toButton); addView(custom)
            custom.visibility = if (range.selectedItemPosition == RangeExport.Preset.CUSTOM.ordinal) View.VISIBLE else View.GONE
            names = CheckBox(this@RangeExportActivity).apply {
                text = "Include device names"
                isChecked = savedInstanceState?.getBoolean("names") ?: false
            }
            addView(names)
            addView(label("Devices use aliases by default. Names include only recognized models already stored; unavailable or custom names stay blank. Both custom dates are included in your local time zone.", 13f))
            exportButton = Button(this@RangeExportActivity).apply {
                text = "Export CSV…"
                setOnClickListener { prepare() }
            }
            addView(exportButton)
            status = label("")
            status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(status)
        }
        updateDates()
        job?.owner = this
        if (interrupted) statusMessage = "Export request was interrupted. Choose Export again."
        updateJob()
    }

    private fun updateDates() {
        fromButton.text = "From: $from"
        toButton.text = "Through: $to"
    }

    private fun pickDate(start: Boolean) {
        val date = if (start) from else to
        DatePickerDialog(this, { _, year, month, day ->
            val selected = LocalDate.of(year, month + 1, day)
            if (start) from = selected else to = selected
            updateDates()
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    private fun prepare() {
        job = null
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val bounds = try {
            RangeExport.resolve(RangeExport.Preset.entries[range.selectedItemPosition], now, zone, from, to)
        } catch (e: IllegalArgumentException) {
            statusMessage = e.message ?: ""
            updateJob()
            return
        }
        job = ExportJob(RangeExport.Request(bounds, zone, names.isChecked)).also {
            it.owner = this
            it.prepare(applicationContext)
        }
        updateJob()
    }

    private fun updateJob() {
        val current = job
        val busy = current != null && current.phase != Phase.DONE
        range.isEnabled = !busy; names.isEnabled = !busy
        fromButton.isEnabled = !busy; toButton.isEnabled = !busy; exportButton.isEnabled = !busy
        if (current != null) statusMessage = current.message
        status.text = current?.message ?: statusMessage
        if (resumed && current?.phase == Phase.READY) {
            current.phase = Phase.PICKING
            current.message = "Choose where to save the analysis CSV."
            status.text = current.message
            try {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/csv"
                    putExtra(Intent.EXTRA_TITLE, "battery-lab-analysis.csv")
                }, CREATE_CSV)
            } catch (e: Exception) {
                current.finish("Couldn't open the file picker. Choose Export again.")
            }
        }
    }

    @Deprecated("Platform result API keeps this plain Views screen dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CREATE_CSV) return
        val current = job
        if (current == null || current.phase != Phase.PICKING) {
            statusMessage = "Export request was interrupted. Choose Export again."
            status.text = statusMessage
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) current.finish("Export cancelled.")
        else current.copy(applicationContext, uri)
        updateJob()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("range", range.selectedItemPosition)
        outState.putBoolean("names", names.isChecked)
        outState.putString("statusMessage", statusMessage)
        outState.putString("from", from.toString()); outState.putString("to", to.toString())
        job?.takeIf { it.phase != Phase.DONE }?.let { outState.putBundle("job", it.save()) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateJob()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    @Deprecated("Retains only worker state; never retains the destroyed Activity")
    override fun onRetainNonConfigurationInstance(): Any? = job

    override fun onDestroy() {
        job?.owner = null
        if (isFinishing) job?.cancel()
        super.onDestroy()
    }

    private enum class Phase { PREPARING, READY, PICKING, COPYING, DONE }

    /** Worker owns only application context. Its result is delivered to the current Activity. */
    private class ExportJob(val request: RangeExport.Request) {
        var owner: RangeExportActivity? = null // main thread only
        var phase = Phase.PREPARING
        var message = "Preparing analysis CSV…"
        private var file: File? = null
        private var maxId: Long? = null
        @Volatile private var cancelled = false

        fun prepare(context: Context) {
            Background.io.execute {
                var staged: File? = null
                try {
                    check(!cancelled)
                    val store = BatteryStore.get(context)
                    val cutoff = store.rangeExportMaxId()
                    staged = File.createTempFile("analysis-", ".csv", context.cacheDir)
                    staged.bufferedWriter(Charsets.UTF_8).use { writer ->
                        val encoder = RangeExport.Encoder(writer, request)
                        store.rangeExportReadings(request.bounds, cutoff) { reading ->
                            check(!cancelled)
                            encoder.write(reading)
                        }
                    }
                    val completed = staged
                    Background.onMain {
                        if (cancelled) completed.delete() else {
                            maxId = cutoff; file = completed; phase = Phase.READY
                            owner?.updateJob()
                        }
                    }
                } catch (e: Exception) {
                    staged?.delete()
                    Background.onMain { finish("Couldn't prepare the CSV. History is unchanged; choose Export again.") }
                }
            }
        }

        fun copy(context: Context, uri: Uri) {
            val staged = file
            if (staged == null || !staged.isFile) {
                finish("Prepared CSV is unavailable. Choose Export again.")
                return
            }
            phase = Phase.COPYING; message = "Saving analysis CSV…"
            Background.io.execute {
                val result = try {
                    check(!cancelled)
                    staged.inputStream().use { input ->
                        (context.contentResolver.openOutputStream(uri, "wt") ?: error("Output unavailable")).use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                check(!cancelled)
                                val size = input.read(buffer)
                                if (size < 0) break
                                output.write(buffer, 0, size)
                            }
                        }
                    }
                    "Analysis CSV exported. It cannot be restored as a backup."
                } catch (e: Exception) { "Export failed; the destination may be incomplete. History is unchanged." }
                finally { staged.delete() }
                Background.onMain { finish(result) }
            }
        }

        fun finish(result: String) {
            file?.delete(); file = null; phase = Phase.DONE; message = result
            owner?.updateJob()
        }

        fun cancel() { cancelled = true; if (phase != Phase.COPYING) file?.delete() }

        fun save() = Bundle().apply {
            putString("request", request.saved())
            putString("phase", phase.name); putString("file", file?.name)
            maxId?.let { putLong("maxId", it) }
        }

        companion object {
            fun restore(context: Context, state: Bundle): ExportJob {
                val request = RangeExport.Request.restore(requireNotNull(state.getString("request")))
                return ExportJob(request).apply {
                    maxId = if (state.containsKey("maxId")) state.getLong("maxId") else null
                    file = state.getString("file")?.takeIf { it.matches(Regex("analysis-[a-zA-Z0-9-]+\\.csv")) }
                        ?.let { File(context.cacheDir, it) }
                    val previous = state.getString("phase")
                    if (previous in listOf(Phase.READY.name, Phase.PICKING.name) && file?.isFile == true && maxId != null) {
                        phase = Phase.valueOf(previous!!)
                        message = "Choose where to save the analysis CSV."
                    } else finish(if (previous == Phase.COPYING.name)
                        "Export was interrupted; the destination may be incomplete. Choose Export again."
                        else "Export was interrupted. Choose Export again.")
                }
            }
        }
    }

    companion object { private const val CREATE_CSV = 1 }
}
