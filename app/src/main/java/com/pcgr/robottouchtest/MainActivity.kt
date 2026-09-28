package com.pcgr.robottouchtest

import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class Screen { SETTINGS, RESULTS, TEST }

    private val configStore by lazy { ConfigStore(this) }
    private val dataStore by lazy { TouchDataStore(this) }
    private var screen = Screen.TEST
    private var exportContent = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::handleBack)
        }
        TouchTestLogic.selfCheck()
        startTest(configStore.load())
    }

    private fun showSettings() {
        screen = Screen.SETTINGS
        val saved = configStore.load()
        val layout = column()
        layout.addView(title("Settings"))
        val fixedPosition = toggle(layout, "Fixed target positions", !saved.randomPosition)
        val randomPosition = toggle(layout, "Random target positions", saved.randomPosition)
        val randomSize = toggle(layout, "Random target sizes", saved.randomSize)
        val trios = field(layout, "RGB trios per spawn", saved.triosPerSpawn.toString(), false)
        val fixedRadiusFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fixedRadius = field(fixedRadiusFields, "Target radius (dp)", saved.maxRadiusDp.clean(), true)
        fixedRadiusFields.addView(body("Default value: ${TestConfig().maxRadiusDp.clean()} dp"))
        layout.addView(fixedRadiusFields)
        val randomRadiusFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val minRadius = field(randomRadiusFields, "Minimum target radius (dp)", saved.minRadiusDp.clean(), true)
        val maxRadius = field(randomRadiusFields, "Maximum target radius (dp)", saved.maxRadiusDp.clean(), true)
        layout.addView(randomRadiusFields)
        fun showRadiusFields(random: Boolean) {
            fixedRadiusFields.visibility = if (random) View.GONE else View.VISIBLE
            randomRadiusFields.visibility = if (random) View.VISIBLE else View.GONE
        }
        showRadiusFields(saved.randomSize)
        randomSize.setOnCheckedChangeListener { _, checked -> showRadiusFields(checked) }
        fixedPosition.setOnCheckedChangeListener { _, checked ->
            if (checked) randomPosition.isChecked = false else if (!randomPosition.isChecked) fixedPosition.isChecked = true
        }
        randomPosition.setOnCheckedChangeListener { _, checked ->
            if (checked) fixedPosition.isChecked = false else if (!fixedPosition.isChecked) randomPosition.isChecked = true
        }

        fun readConfig(): TestConfig? {
            val config = runCatching {
                val radius = fixedRadius.text.toString().toFloat()
                TestConfig(
                    targetCount = Math.multiplyExact(trios.text.toString().toInt(), 3),
                    minRadiusDp = if (randomSize.isChecked) minRadius.text.toString().toFloat() else radius,
                    maxRadiusDp = if (randomSize.isChecked) maxRadius.text.toString().toFloat() else radius,
                    randomPosition = randomPosition.isChecked,
                    randomSize = randomSize.isChecked,
                )
            }.getOrElse {
                message("Invalid settings", "Enter a valid number in every numeric field.")
                return null
            }
            config.error()?.let { message("Invalid settings", it); return null }
            return config
        }

        layout.addView(button("Save and Start Test") {
            readConfig()?.let {
                configStore.save(it)
                startTest(it)
            }
        })
        layout.addView(button("Results and Data") { showResults() })
        layout.addView(button("Back to Test") { startTest(saved) })
        setContentView(scroll(layout))
    }

    private fun startTest(config: TestConfig) {
        val error = config.error() ?: runCatching {
            val metrics = resources.displayMetrics
            TouchTestLogic.generateTargets(config, metrics.widthPixels, metrics.heightPixels, metrics.density, 136 * metrics.density, metrics.heightPixels - 24 * metrics.density)
        }.exceptionOrNull()?.message
        if (error != null) return message("Cannot start test", error)
        screen = Screen.TEST
        val test = RobotTouchTestView(this, config, ::showFinalSummary)
        setContentView(FrameLayout(this).apply {
            addView(test, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(button("Settings") { showSettings() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(32)
                marginEnd = dp(8)
            })
        })
    }

    private fun showFinalSummary(result: RobotTouchTestView.SessionResult) {
        val attempts = result.hits + result.misses
        val summary = "Targets: ${result.targetCount}\nTouch attempts: $attempts\nHits: ${result.hits}\nMisses: ${result.misses}\n" +
            "Accuracy: ${percent(result.hits, attempts)}\nAverage response time: ${result.averageResponseMs} ms"
        val dialog = AlertDialog.Builder(this).setTitle("Test Complete").setMessage(summary)
            .setPositiveButton("Start New Test") { _, _ -> startTest(configStore.load()) }
        dialog.setCancelable(false).show()
    }

    private fun showResults() {
        screen = Screen.RESULTS
        val records = dataStore.records()
        val sessions = records.groupBy { it.optString("session_id") }
        val targets = records.distinctBy { "${it.optString("session_id")}:${it.optString("target_id")}" }.size
        val hits = records.count { it.optBoolean("inside_target") }
        val distances = records.filterNot { it.isNull("distance_to_center") }.map { it.optDouble("distance_to_center") }
        val responses = records.map { it.optLong("response_time_ms") }
        val selected = mutableSetOf<String>()
        val layout = column()
        layout.addView(title("Results and Data"))
        layout.addView(body(
            "Sessions: ${sessions.size}\nTargets: $targets\nTouch attempts: ${records.size}\nHits: $hits\nMisses: ${records.size - hits}\n" +
                "Accuracy: ${percent(hits, records.size)}\nAverage touch error: ${average(distances)} px\n" +
                "Average response time: ${if (responses.isEmpty()) "0" else responses.average().roundToInt()} ms"
        ))
        layout.addView(section("By target size", grouped(records) { "${it.optDouble("target_radius_dp").roundToInt()} dp" }))
        layout.addView(section("By test mode", grouped(records) { it.optString("test_mode", "Unknown") }))
        layout.addView(section("By target position", grouped(records, ::positionName)))
        layout.addView(label("Touch tendency (green = hit, red = miss)"))
        layout.addView(TouchMapView(this, records))
        layout.addView(label("Previous test sessions"))
        sessions.entries.sortedByDescending { it.value.firstOrNull()?.optString("date_time") }.forEach { (id, rows) ->
            layout.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val check = CheckBox(this@MainActivity).apply {
                    text = "${rows.first().optString("date_time").take(19)} · ${rows.size} attempts"
                    setOnCheckedChangeListener { _, checked -> if (checked) selected += id else selected -= id }
                }
                addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(button("View") { showSessionSummary(rows) })
            })
        }
        layout.addView(button("Delete Selected Sessions") {
            if (selected.isEmpty()) message("Nothing selected", "Select one or more sessions first.")
            else confirm("Delete selected sessions?", "This cannot be undone.") { dataStore.deleteSessions(selected); showResults() }
        })
        layout.addView(button("Clear All Saved Data") {
            confirm("Clear all saved data?", "All ${records.size} saved attempts will be deleted. This cannot be undone.") {
                dataStore.clear(); showResults()
            }
        })
        layout.addView(button("Export All Data as JSON") { createDocument("touch-results.json", dataStore.exportJson(), "application/json", EXPORT_JSON) })
        layout.addView(button("Export All Data as CSV") { createDocument("touch-results.csv", dataStore.exportCsv(), "text/csv", EXPORT_CSV) })
        layout.addView(button("Import JSON Data") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) }, IMPORT_JSON)
        })
        layout.addView(body("Local data file: ${dataStore.file.absolutePath}"))
        layout.addView(button("Back to Settings") { showSettings() })
        setContentView(scroll(layout))
    }

    private fun showSessionSummary(rows: List<JSONObject>) {
        val hits = rows.count { it.optBoolean("inside_target") }
        val targets = rows.distinctBy { it.optString("target_id") }.size
        val responses = rows.map { it.optLong("response_time_ms") }
        message("Session Summary", "Session ID: ${rows.first().optString("session_id")}\nMode: ${rows.first().optString("test_mode")}\n" +
            "Date: ${rows.first().optString("date_time")}\nTargets: $targets\nTouch attempts: ${rows.size}\nHits: $hits\nMisses: ${rows.size - hits}\n" +
            "Accuracy: ${percent(hits, rows.size)}\nAverage response time: ${responses.average().roundToInt()} ms")
    }

    private fun grouped(records: List<JSONObject>, key: (JSONObject) -> String): String = records.groupBy(key).entries
        .sortedBy { it.key }.joinToString("\n") { (name, rows) ->
            val hits = rows.count { it.optBoolean("inside_target") }
            "$name: ${rows.size} attempts, ${percent(hits, rows.size)} accuracy"
        }.ifEmpty { "No data" }

    private fun positionName(row: JSONObject): String {
        fun band(value: Double, size: Double, labels: Array<String>) = labels[((value / size * 3).toInt()).coerceIn(0, 2)]
        return "${band(row.optDouble("target_center_y"), row.optDouble("screen_height", 1.0), arrayOf("Top", "Middle", "Bottom"))} " +
            band(row.optDouble("target_center_x"), row.optDouble("screen_width", 1.0), arrayOf("left", "center", "right"))
    }

    private fun createDocument(name: String, content: String, type: String, request: Int) {
        exportContent = content
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            this.type = type; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, name)
        }, request)
    }

    @Deprecated("Uses the platform document picker without an additional dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        runCatching {
            if (requestCode == IMPORT_JSON) {
                val text = contentResolver.openInputStream(data.data!!)!!.bufferedReader().use { it.readText() }
                val count = dataStore.importJson(text)
                Toast.makeText(this, "$count records imported.", Toast.LENGTH_LONG).show()
                showResults()
            } else {
                contentResolver.openOutputStream(data.data!!, "wt")!!.bufferedWriter().use { it.write(exportContent) }
                Toast.makeText(this, "Export complete.", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { message("File error", it.message ?: "The file could not be processed.") }
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Handled by the platform callback on Android 13+")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        when (screen) {
            Screen.SETTINGS -> startTest(configStore.load())
            Screen.RESULTS -> showSettings()
            Screen.TEST -> confirm("Exit the application?", "Completed attempts are already safely stored.") { finishAfterTransition() }
        }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(32))
    }

    private fun scroll(child: View) = ScrollView(this).apply { addView(child) }
    private fun title(text: String) = TextView(this).apply { this.text = text; textSize = 28f; setTextColor(Color.rgb(24, 33, 45)); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(18)) }
    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 17f; setTextColor(Color.rgb(24, 33, 45)); setPadding(0, dp(14), 0, dp(4)) }
    private fun body(text: String) = TextView(this).apply { this.text = text; textSize = 16f; setTextColor(Color.rgb(55, 65, 81)); setPadding(0, dp(8), 0, dp(14)) }
    private fun section(name: String, text: String) = body("$name\n$text")
    private fun button(text: String, click: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { click() } }

    private fun field(parent: LinearLayout, name: String, value: String, decimal: Boolean): EditText {
        parent.addView(label(name))
        return EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
            contentDescription = name
            parent.addView(this)
        }
    }

    @Suppress("DEPRECATION")
    private fun toggle(parent: LinearLayout, name: String, checked: Boolean) = Switch(this).apply {
        text = name; isChecked = checked; setPadding(0, dp(8), 0, dp(8)); parent.addView(this)
    }

    private fun message(title: String, text: String) { AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show() }
    private fun confirm(title: String, text: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Confirm") { _, _ -> action() }.setNegativeButton("Cancel", null).show()
    }
    private fun average(values: List<Double>) = if (values.isEmpty()) "0.0" else String.format(Locale.US, "%.1f", values.average())
    private fun percent(hits: Int, total: Int) = if (total == 0) "0.0%" else String.format(Locale.US, "%.1f%%", hits * 100.0 / total)
    private fun Float.clean() = if (this % 1f == 0f) toInt().toString() else toString()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val EXPORT_JSON = 10
        const val EXPORT_CSV = 11
        const val IMPORT_JSON = 12
    }
}

private class TouchMapView(context: android.content.Context, private val records: List<JSONObject>) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = "Touch offset plot. Green points are hits and red points are misses."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (260 * density).roundToInt())

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(240, 242, 245))
        val cx = width / 2f
        val cy = height / 2f
        val referenceRadius = minOf(width, height) * .23f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density; paint.color = Color.DKGRAY
        canvas.drawCircle(cx, cy, referenceRadius, paint)
        canvas.drawLine(cx - referenceRadius * 1.5f, cy, cx + referenceRadius * 1.5f, cy, paint)
        canvas.drawLine(cx, cy - referenceRadius * 1.5f, cx, cy + referenceRadius * 1.5f, paint)
        paint.style = Paint.Style.FILL
        records.filterNot { it.isNull("actual_touch_x") || it.isNull("actual_touch_y") }.forEach { row ->
            val radius = row.optDouble("target_radius", 1.0).coerceAtLeast(1.0)
            val dx = ((row.optDouble("actual_touch_x") - row.optDouble("target_center_x")) / radius * referenceRadius).toFloat()
            val dy = ((row.optDouble("actual_touch_y") - row.optDouble("target_center_y")) / radius * referenceRadius).toFloat()
            paint.color = if (row.optBoolean("inside_target")) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40)
            canvas.drawCircle((cx + dx).coerceIn(5f, width - 5f), (cy + dy).coerceIn(5f, height - 5f), 4 * density, paint)
        }
    }
}
