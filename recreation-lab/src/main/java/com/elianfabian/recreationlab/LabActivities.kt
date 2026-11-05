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
import com.elianfabian.locale_easy.LocaleEasy
import java.io.File
import java.util.Locale

class HarnessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LabSession.case = intent.getStringExtra("case") ?: "recreate"
        LabSession.mode = intent.getStringExtra("mode") ?: "timing"
        require(LabSession.case in LabSession.cases)
        require(LabSession.mode in LabSession.modes)
        val runId = intent.getStringExtra("run_id") ?: "interactive"
        require(runId.matches(Regex("[A-Za-z0-9_-]+")))
        LabSession.directory = File(filesDir, "runs/$runId").apply { mkdirs() }
        LabSession.rows = intent.getIntExtra("rows", 200).also { require(it in 1..2000) }
        val systemLocales = if (Build.VERSION.SDK_INT >= 33) getSystemService(LocaleManager::class.java).systemLocales
            else android.content.res.Resources.getSystem().configuration.locales
        LabSession.targetLocale = if (systemLocales[0].language == "fr") Locale.ENGLISH else Locale.FRENCH
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
            startActivity(Intent(this, WorkloadActivity::class.java))
            finish()
        }, 500)
    }
}

class WorkloadActivity : Activity() {
    val instanceId = LabSession.instance()
    private lateinit var root: View

    override fun attachBaseContext(newBase: Context) {
        val base = if (LabSession.case == "localeeasy") LocaleEasy.getInstance().localizedContext(newBase)
        else if (LabSession.overrideLocale) localized(newBase) else newBase
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) = traced("onCreate") {
        LabSession.event("onCreate", instanceId, "restored=${savedInstanceState != null}")
        super.onCreate(savedInstanceState)
        installWorkload(this)
        if (savedInstanceState == null) root.findViewById<EditText>(R.id.lab_input).setText("typed fixture input")
    }

    private fun installWorkload(context: Context) = traced("buildContent") {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = context.getString(R.string.lab_title); textSize = 24f })
            addView(EditText(context).apply { id = R.id.lab_input; hint = context.getString(R.string.lab_hint); inputType = 1 })
            repeat(LabSession.rows) { index ->
                addView(TextView(context).apply { text = "${context.getString(R.string.lab_row)} $index"; textSize = 16f })
            }
        }
        root = ScrollView(context).apply { id = R.id.lab_root; addView(column) }
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
                Handler(Looper.getMainLooper()).postDelayed({ LabSession.prepare(this, installedRoot) }, 500)
            } else LabSession.committed(this, installedRoot)
        }
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
