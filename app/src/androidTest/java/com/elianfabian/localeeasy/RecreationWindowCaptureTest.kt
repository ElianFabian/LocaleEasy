package com.elianfabian.localeeasy

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
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
            for (framework in listOf(false, true)) {
                instrumentation.runOnMainSync { api.useFrameworkLocaleRecreation = framework; api.followSystem() }
                instrumentation.waitForIdleSync()
                ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
                    instrumentation.waitForIdleSync()
                    var original: SampleActivity? = null
                    scenario.onActivity { original = it }
                    val running = AtomicBoolean(true)
                    val ready = CountDownLatch(1)
                    val frames = mutableListOf<Pair<Long, Bitmap>>()
                    val capture = Thread {
                        try {
                            while (running.get()) {
                                val time = SystemClock.elapsedRealtimeNanos()
                                instrumentation.uiAutomation.takeScreenshot()?.let { full ->
                                    val small = Bitmap.createScaledBitmap(full, 180, 400, true)
                                    full.recycle()
                                    frames.add(time to small)
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
                            api.setLocale(if (api.effectiveLocales.value.first().language == "fr") Locale.ITALIAN else Locale.FRENCH)
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
                        val name = if (framework) "framework" else "manual"
                        val metadata = JSONArray()
                        frames.forEachIndexed { index, (time, bitmap) ->
                            val file = "$name-$index.png"
                            File(directory, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            metadata.put(JSONObject().put("file", file).put("trigger_offset_ms", (time - trigger) / 1_000_000.0))
                            bitmap.recycle()
                        }
                        assertTrue("No screenshots were captured", frames.isNotEmpty())
                        File(directory, "$name.json").writeText(metadata.toString(2))
                        scenario.onActivity {
                            File(directory, "$name-state.json").writeText(JSONObject()
                                .put("activity_locales", it.resources.configuration.locales.toLanguageTags())
                                .put("view_text", it.findViewById<android.widget.TextView>(R.id.textViewLocalized).text)
                                .put("preference", api.preference.value.toString()).toString(2))
                        }
                    } finally {
                        running.set(false)
                        capture.join(5_000)
                        frames.forEach { if (!it.second.isRecycled) it.second.recycle() }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { api.followSystem(); api.useFrameworkLocaleRecreation = true }
        }
    }
}
