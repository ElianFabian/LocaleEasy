package com.elianfabian.localeeasy

import android.graphics.Bitmap
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.localeeasy.ui.legacy.SampleActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in visual diagnostic; screenshot sampling perturbs timing and can miss short flashes. */
@RunWith(AndroidJUnit4::class)
class RecreationWindowCaptureTest {
    @Test fun captureLocaleReplacementFrames() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureWindows") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = LocaleEasy.getInstance()
        val directory = File(instrumentation.targetContext.filesDir, "window-captures").apply { mkdirs() }
        try {
            val name = "manual"
            instrumentation.runOnMainSync { api.followSystem() }
            instrumentation.waitForIdleSync()
            ActivityScenario.launch<SampleActivity>(Intent(instrumentation.targetContext, SampleActivity::class.java)).use { scenario ->
                instrumentation.waitForIdleSync()
                var original: SampleActivity? = null
                var originalDecor: android.view.View? = null
                var originalRoot: android.view.View? = null
                scenario.onActivity {
                    original = it
                    originalDecor = it.window.decorView
                    originalRoot = it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
                }
                val running = AtomicBoolean(true)
                val ready = CountDownLatch(1)
                data class Frame(val start: Long, val end: Long, val bitmap: Bitmap)
                val frames = mutableListOf<Frame>()
                val capture = Thread {
                    try {
                        while (running.get()) {
                            val time = SystemClock.elapsedRealtimeNanos()
                            Trace.beginSection("LocaleEasy.screenshot.$name")
                            val screenshot = try { instrumentation.uiAutomation.takeScreenshot() } finally { Trace.endSection() }
                            val captured = SystemClock.elapsedRealtimeNanos()
                            screenshot?.let { full ->
                                val small = Bitmap.createScaledBitmap(full, 180, 400, true)
                                full.recycle()
                                frames.add(Frame(time, captured, small))
                                ready.countDown()
                            }
                            Thread.sleep(20)
                        }
                    } finally { ready.countDown() }
                }
                capture.start()
                try {
                    assertTrue("Screenshot sampling did not start", ready.await(5, TimeUnit.SECONDS))
                    val trigger = SystemClock.elapsedRealtimeNanos()
                    instrumentation.runOnMainSync {
                        Trace.beginSection("LocaleEasy.change.$name")
                        try { api.setLocale(if (api.effectiveLocales.value.first().language == "fr") Locale.ITALIAN else Locale.FRENCH) }
                        finally { Trace.endSection() }
                    }
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    var replaced = false
                    while (!replaced && SystemClock.uptimeMillis() < deadline) {
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { replaced = it !== original }
                        Thread.sleep(30)
                    }
                    assertTrue("Activity did not change", replaced)
                    Thread.sleep(700) // Capture the settled appearance as well as the replacement.
                    running.set(false)
                    capture.join(5_000)
                    assertTrue("Screenshot worker did not stop", !capture.isAlive)
                    val metadata = JSONArray()
                    frames.forEachIndexed { index, (time, captured, bitmap) ->
                        val file = "$name-$index.png"
                        File(directory, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        metadata.put(JSONObject().put("file", file).put("trigger_offset_ms", (time - trigger) / 1_000_000.0)
                            .put("capture_start_ns", time).put("capture_end_ns", captured))
                        bitmap.recycle()
                    }
                    assertTrue("No screenshots were captured", frames.isNotEmpty())
                    File(directory, "$name.json").writeText(metadata.toString(2))
                    scenario.onActivity {
                        File(directory, "$name-state.json").writeText(JSONObject()
                            .put("activity_locales", it.resources.configuration.locales.toLanguageTags())
                            .put("trigger_ns", trigger)
                            .put("activity_replaced", it !== original)
                            .put("decor_replaced", it.window.decorView !== originalDecor)
                            .put("root_replaced", it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) !== originalRoot)
                            .put("view_text", it.findViewById<android.widget.TextView>(R.id.textViewLocalized).text)
                            .put("preference", api.preference.value.toString())
                            .put("framework_backend", false)
                            .put("framework_locales", it.getSystemService(android.app.LocaleManager::class.java).applicationLocales.toLanguageTags())
                            .put("handled_config_mask", it.packageManager.getActivityInfo(it.componentName, 0).configChanges).toString(2))
                    }
                } finally {
                    running.set(false)
                    capture.join(5_000)
                    frames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                }
            }
        } finally {
            instrumentation.runOnMainSync { api.followSystem() }
        }
    }
}
