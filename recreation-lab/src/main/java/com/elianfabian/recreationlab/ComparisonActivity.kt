package com.elianfabian.recreationlab

import android.app.Activity
import android.app.ActivityManager
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Interactive controls live outside the fresh process used for each measured trial. */
class ComparisonActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("lab_ui", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val languages = listOf(Locale.ENGLISH, Locale.FRENCH)
    private val rowCounts = listOf(50, 200, 500)
    private lateinit var language: Spinner
    private lateinit var workload: Spinner
    private lateinit var manual: Button
    private lateinit var framework: Button
    private lateinit var rotation: Button
    private lateinit var status: TextView
    private lateinit var manualHistory: TextView
    private lateinit var frameworkHistory: TextView
    private var resumed = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureInteractiveWindow()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun label(text: CharSequence, size: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.rgb(29, 43, 56))
            setPadding(0, dp(8), 0, dp(8))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            column.addView(this)
        }
        label(getString(R.string.ui_title), 28f, true).isAccessibilityHeading = true
        label(getString(R.string.ui_intro))
        label(getString(R.string.ui_device, Build.VERSION.RELEASE, Build.VERSION.SDK_INT), 13f)
        label(getString(R.string.ui_language), bold = true)
        language = Spinner(this).apply {
            id = R.id.ui_language
            adapter = ArrayAdapter(this@ComparisonActivity, android.R.layout.simple_spinner_dropdown_item,
                languages.map { it.getDisplayLanguage(Locale.ENGLISH) })
            val preferred = preferences.getString("language", null)
            val default = if (systemLanguage() == "fr") 0 else 1
            setSelection(languages.indexOfFirst { it.language == preferred }.takeIf { it >= 0 } ?: default)
        }
        column.addView(language)
        label(getString(R.string.ui_workload), bold = true)
        workload = Spinner(this).apply {
            id = R.id.ui_rows
            adapter = ArrayAdapter(this@ComparisonActivity, android.R.layout.simple_spinner_dropdown_item,
                rowCounts.map { getString(R.string.ui_rows_label, it) })
            setSelection(rowCounts.indexOf(preferences.getInt("rows", 200)).coerceAtLeast(0))
        }
        column.addView(workload)
        fun action(id: Int, title: Int, case: String): Button = Button(this).apply {
            this.id = id
            setText(title)
            setOnClickListener { beginTrial(case) }
            column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        manual = action(R.id.ui_manual, R.string.ui_manual, "manual_locale")
        label(getString(R.string.ui_manual_detail), 13f)
        framework = action(R.id.ui_framework, R.string.ui_framework, "framework_locale")
        label(getString(if (Build.VERSION.SDK_INT >= 33) R.string.ui_framework_detail else R.string.ui_framework_unavailable), 13f)
        rotation = action(R.id.ui_rotation, R.string.ui_rotation, "rotation")
        label(getString(R.string.ui_rotation_detail), 13f)
        status = label("").apply { id = R.id.ui_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        label(getString(R.string.ui_latest), 20f, true)
        manualHistory = label("").apply { id = R.id.ui_manual_result }
        frameworkHistory = label("").apply { id = R.id.ui_framework_result }
        listOf(manualHistory, frameworkHistory).forEach {
            it.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
            it.setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        label(getString(R.string.ui_measurement_note), 13f)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(244, 246, 248))
            addView(column)
            applySystemBarPadding()
        })
        language.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                preferences.edit().putString("language", languages[position].language).apply()
                updateAvailability()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        busy = false
        preferences.getString("pending", null)?.let { run ->
            val result = readResult(run)
            if (result != null) preferences.edit().putString("last_${result.getString("case")}", run).apply()
            preferences.edit().remove("pending").apply()
        }
        manualHistory.text = history("manual_locale")
        frameworkHistory.text = history("framework_locale")
        updateAvailability()
    }

    override fun onPause() { resumed = false; super.onPause() }

    private fun systemLanguage(): String = if (Build.VERSION.SDK_INT >= 33) {
        getSystemService(LocaleManager::class.java).systemLocales[0].language
    } else android.content.res.Resources.getSystem().configuration.locales[0].language

    private fun updateAvailability() {
        val changesLanguage = languages[language.selectedItemPosition].language != systemLanguage()
        manual.isEnabled = !busy && changesLanguage
        framework.isEnabled = !busy && changesLanguage && Build.VERSION.SDK_INT >= 33
        rotation.isEnabled = !busy
        language.isEnabled = !busy
        workload.isEnabled = !busy
        status.setText(if (busy) R.string.ui_preparing else if (!changesLanguage) R.string.ui_same_language else R.string.ui_start_hint)
    }

    private fun beginTrial(case: String) {
        if (busy) return
        busy = true
        updateAvailability()
        val manager = getSystemService(ActivityManager::class.java)
        // Only terminate this app's dedicated trial process; the launcher stays alive.
        val old = manager.runningAppProcesses.orEmpty().filter {
            it.uid == Process.myUid() && it.processName == "$packageName:trial"
        }.map { it.pid }.toSet()
        old.forEach { Process.killProcess(it) }
        val deadline = SystemClock.uptimeMillis() + 3_000
        fun launchWhenCold() {
            if (!resumed || isFinishing) { busy = false; return }
            if (manager.runningAppProcesses.orEmpty().any { it.pid in old }) {
                if (SystemClock.uptimeMillis() >= deadline) {
                    busy = false
                    updateAvailability()
                    status.setText(R.string.ui_reset_failed)
                } else handler.postDelayed({ launchWhenCold() }, 25)
                return
            }
            val run = "ui_${case}_${System.currentTimeMillis()}"
            val count = rowCounts[workload.selectedItemPosition]
            preferences.edit().putString("pending", run).putInt("rows", count).apply()
            startActivity(Intent(this, InteractiveHarnessActivity::class.java)
                .putExtra("interactive", true).putExtra("case", case).putExtra("mode", "timing")
                .putExtra("run_id", run).putExtra("target_locale", languages[language.selectedItemPosition].toLanguageTag())
                .putExtra("rows", count))
        }
        launchWhenCold()
    }

    private fun readResult(run: String): JSONObject? {
        if (!run.matches(Regex("[A-Za-z0-9_-]+"))) return null
        return runCatching { JSONObject(File(filesDir, "runs/$run/result.json").readText()) }.getOrNull()
    }

    private fun history(case: String): String {
        val result = preferences.getString("last_$case", null)?.let(::readResult)
        return caseTitle(case) + "\n" + (result?.let {
            "${it.getString("target_locale")} · ${it.getInt("rows")} rows\n" + trialSummary(this, it)
        } ?: getString(R.string.ui_no_result))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

internal fun caseTitle(case: String): String = when (case) {
    "manual_locale" -> "Manual recreation"
    "framework_locale" -> "Framework recreation"
    "rotation" -> "Rotation control"
    else -> case
}

internal fun Activity.configureInteractiveWindow() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
    }
}

internal fun View.applySystemBarPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    post { ViewCompat.requestApplyInsets(this) }
}

internal fun trialSummary(context: Context, result: JSONObject): String {
    if (!result.has("final_view")) return "${result.optString("status", "FAIL")}: ${result.optString("error", "Run did not complete")}"
    val before = result.getJSONObject("initial_view")
    val after = result.getJSONObject("final_view")
    val summary = context.getString(R.string.ui_result_format, result.getString("status"), result.getDouble("first_frame_commit_ms"),
        before.getInt("activity_id") != after.getInt("activity_id"), before.getInt("root_id") != after.getInt("root_id"),
        after.getString("view_locales"), before.getString("input") == after.getString("input"),
        result.getJSONObject("after").getInt("pss_kb") - result.getJSONObject("before").getInt("pss_kb"))
    return if (result.getString("status") == "PASS") summary else summary + "\n" + result.optJSONArray("errors")
}
