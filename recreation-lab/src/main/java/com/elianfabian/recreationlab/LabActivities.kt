package com.elianfabian.recreationlab

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import org.json.JSONObject
import com.elianfabian.locale_easy.LocaleEasy
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale

open class HarnessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LabSession.case = intent.getStringExtra("case") ?: "recreate"
        LabSession.mode = intent.getStringExtra("mode") ?: "timing"
        LabSession.interactive = intent.getBooleanExtra("interactive", false)
        require(LabSession.case in LabSession.cases)
        require(LabSession.mode in LabSession.modes)
        val runId = intent.getStringExtra("run_id") ?: "interactive"
        require(runId.matches(Regex("[A-Za-z0-9_-]+")))
        LabSession.directory = File(filesDir, "runs/$runId").apply { mkdirs() }
        LabSession.rows = intent.getIntExtra("rows", 200).also { require(it in 1..2000) }
        val systemLocales = if (Build.VERSION.SDK_INT >= 33) getSystemService(LocaleManager::class.java).systemLocales
            else android.content.res.Resources.getSystem().configuration.locales
        LabSession.targetLocale = if (systemLocales[0].language == "fr") Locale.ENGLISH else Locale.FRENCH
        intent.getStringExtra("target_locale")?.let { tag ->
            LabSession.targetLocale = Locale.forLanguageTag(tag).also { require(it.language.isNotEmpty()) }
        }
        if (LabSession.case == "framework_locale" && Build.VERSION.SDK_INT < 33) {
            LabSession.fail("framework_locale requires API 33+")
            finish()
            return
        }
        // Reset persisted framework preferences before constructing the workload context.
        if (Build.VERSION.SDK_INT >= 33) getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        if (LabSession.case == "localeeasy") { LocaleEasy.install(this); LocaleEasy.getInstance().followSystem() }
        setContentView(TextView(this).apply { text = "Preparing ${LabSession.case}" })
        Handler(Looper.getMainLooper()).postDelayed({
            val target = if (LabSession.interactive) InteractiveWorkloadActivity::class.java else WorkloadActivity::class.java
            startActivity(Intent(this, target))
            finish()
        }, 500)
    }
}

open class WorkloadActivity : Activity() {
    val instanceId = LabSession.instance()
    private lateinit var root: View
    private var applyButton: Button? = null
    private var resultView: TextView? = null

    override fun attachBaseContext(newBase: Context) {
        val base = if (!LabSession.isConfigured) newBase
        else if (LabSession.case == "localeeasy") LocaleEasy.getInstance().localizedContext(newBase)
        else if (LabSession.overrideLocale) localized(newBase) else newBase
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!LabSession.isConfigured) {
            super.onCreate(null)
            startActivity(Intent(this, ComparisonActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            return
        }
        traced("onCreate") {
            LabSession.event("onCreate", instanceId, "restored=${savedInstanceState != null}")
            super.onCreate(savedInstanceState)
            if (LabSession.interactive) {
                LabSession.interactiveActivity = WeakReference(this)
                configureInteractiveWindow()
            }
            installWorkload(this)
            if (savedInstanceState == null) root.findViewById<EditText>(R.id.lab_input).setText("typed fixture input")
        }
    }

    private fun installWorkload(context: Context) = traced("buildContent") {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (LabSession.interactive) {
                val padding = (20 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                addView(TextView(context).apply {
                    text = if (LabSession.case == "rotation") caseTitle(LabSession.case)
                        else getString(R.string.ui_trial_title, caseTitle(LabSession.case), LabSession.targetLocale.getDisplayLanguage(Locale.ENGLISH))
                    textSize = 18f
                })
                addView(Button(context).apply {
                    id = R.id.ui_apply
                    text = if (LabSession.case == "rotation") getString(R.string.ui_apply_rotation)
                        else getString(R.string.ui_apply, LabSession.targetLocale.getDisplayLanguage(Locale.ENGLISH))
                    isEnabled = false
                    setOnClickListener {
                        isEnabled = false
                        LabSession.prepare(this@WorkloadActivity, root)
                    }
                    applyButton = this
                })
                addView(Button(context).apply {
                    id = R.id.ui_back
                    setText(R.string.ui_back)
                    setOnClickListener { finish() }
                })
                addView(TextView(context).apply {
                    id = R.id.ui_trial_result
                    setText(if (LabSession.triggered) R.string.ui_measuring else R.string.ui_ready)
                    minLines = 4
                    resultView = this
                })
            }
            addView(TextView(context).apply { text = context.getString(R.string.lab_title); textSize = 24f })
            addView(EditText(context).apply { id = R.id.lab_input; hint = context.getString(R.string.lab_hint); inputType = 1 })
            repeat(LabSession.rows) { index ->
                addView(TextView(context).apply { text = "${context.getString(R.string.lab_row)} $index"; textSize = 16f })
            }
        }
        root = ScrollView(context).apply {
            id = R.id.lab_root
            addView(column)
            if (LabSession.interactive) applySystemBarPadding()
        }
        setContentView(root)
        val installedRoot = root
        var drawn = false
        installedRoot.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (!drawn) { drawn = true; LabSession.drawn(instanceId) }
            }
        })
        installedRoot.viewTreeObserver.registerFrameCommitCallback {
            if (!LabSession.triggered) {
                if (LabSession.interactive) applyButton?.isEnabled = true
                else Handler(Looper.getMainLooper()).postDelayed({ LabSession.prepare(this, installedRoot) }, 500)
            } else LabSession.committed(this, installedRoot)
        }
    }

    fun showInteractiveResult(result: JSONObject) {
        resultView?.text = trialSummary(this, result)
        applyButton?.setText(if (result.optString("status") == "PASS") R.string.ui_applied else R.string.ui_failed)
    }

    fun performCase() = traced("request.${LabSession.case}") {
        when (LabSession.case) {
            "recreate" -> recreate()
            "rotation" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "manual_locale" -> { LabSession.overrideLocale = true; recreate() }
            "framework_locale" -> if (Build.VERSION.SDK_INT >= 33) {
                getSystemService(LocaleManager::class.java).applicationLocales = LocaleList(LabSession.targetLocale)
            }
            "localeeasy" -> LocaleEasy.getInstance().setLocale(LabSession.targetLocale)
            "view_only" -> {
                val state = SparseArray<Parcelable>()
                root.saveHierarchyState(state)
                installWorkload(localized(this))
                root.restoreHierarchyState(state)
            }
        }
    }

    override fun onStart() = traced("onStart") { super.onStart(); LabSession.event("onStart", instanceId) }
    override fun onResume() = traced("onResume") { super.onResume(); LabSession.event("onResume", instanceId) }
    override fun onPause() = traced("onPause") { LabSession.event("onPause", instanceId); super.onPause() }
    override fun onStop() = traced("onStop") { LabSession.event("onStop", instanceId); super.onStop() }
    override fun onDestroy() = traced("onDestroy") {
        LabSession.event("onDestroy", instanceId, "changing=$isChangingConfigurations")
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) = traced("onSaveInstanceState") {
        LabSession.event("onSaveInstanceState", instanceId)
        super.onSaveInstanceState(outState)
    }

    private fun localized(base: Context): Context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
        setLocale(LabSession.targetLocale)
    })
}

class InteractiveHarnessActivity : HarnessActivity()
class InteractiveWorkloadActivity : WorkloadActivity()
