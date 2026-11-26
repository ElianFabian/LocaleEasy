package com.elianfabian.localeeasy

import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SdkSuppress(minSdkVersion = 24)
class LocaleSheetCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun captureLanguageTransition() {
        val arguments = InstrumentationRegistry.getArguments()
        val mode = arguments.getString("captureLocaleSheet")
        assumeTrue(mode in listOf("local", "system", "closed"))
        val originalSystemLocales = if (mode == "system") {
            LocaleList.forLanguageTags(requireNotNull(arguments.getString("restoreSystemLocales")))
        } else null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = LocaleEasy.getInstance()
        val originalPreference = api.preference.value
        val directory = File(instrumentation.targetContext.cacheDir, "locale-sheet-capture").apply { mkdirs() }
        val automation = instrumentation.uiAutomation
        val ready = CountDownLatch(1)
        val running = AtomicBoolean(true)
        data class Frame(val bitmap: Bitmap, val start: Long, val end: Long)
        val frames = mutableListOf<Frame>()
        val worker = Thread {
            while (running.get()) {
                val start = SystemClock.elapsedRealtimeNanos()
                val screenshot = automation.takeScreenshot()
                val end = SystemClock.elapsedRealtimeNanos()
                if (screenshot != null) {
                    val scaled = Bitmap.createScaledBitmap(screenshot, 270, screenshot.height * 270 / screenshot.width, true)
                    frames.add(Frame(scaled, start, end))
                    if (scaled !== screenshot) screenshot.recycle()
                    ready.countDown()
                }
                SystemClock.sleep(16)
            }
        }
        try {
            compose.runOnUiThread {
                if (originalSystemLocales == null) api.setAppLocale(Locale.ENGLISH) else api.followSystem()
            }
            if (originalSystemLocales != null) {
                changeSystemLocales(instrumentation, originalSystemLocales.withPrimaryLanguage("en"))
                compose.waitUntil(15_000) { api.effectiveLocaleList.value.first().language == "en" }
            }
            if (mode != "closed") {
                compose.onNodeWithText(compose.activity.getString(R.string.open_settings)).performClick()
            }
            val apply = if (mode == "local") {
                compose.onNodeWithText(compose.activity.getString(R.string.follow_system_locale)).performClick()
                compose.onNodeWithText("French").performClick()
                compose.onNodeWithText(compose.activity.getString(R.string.apply_locale_changes)).performScrollTo()
            } else null
            val originalActivity = compose.activity
            compose.waitForIdle()
            worker.start()
            assertTrue("Initial screenshot missing", ready.await(5, TimeUnit.SECONDS))
            val trigger = SystemClock.elapsedRealtimeNanos()
            when {
                apply != null -> apply.performClick()
                originalSystemLocales != null ->
                    changeSystemLocales(instrumentation, originalSystemLocales.withPrimaryLanguage("fr"))
                else -> compose.runOnUiThread { api.setAppLocale(Locale.FRENCH) }
            }
            compose.waitUntil(15_000) {
                compose.activity !== originalActivity && api.effectiveLocaleList.value.first().language == "fr"
            }
            if (mode == "local") compose.onNodeWithText("French").assertIsSelected()
            if (mode != "closed") {
                compose.onNodeWithText(compose.activity.getString(R.string.settings_title)).assertExists()
            }
            SystemClock.sleep(700)
            running.set(false)
            worker.join(5_000)
            assertTrue("Screenshot worker stalled", !worker.isAlive)
            File(directory, "frames.csv").printWriter().use { writer ->
                writer.println("file,startMs,endMs")
                frames.forEachIndexed { index, frame ->
                    val name = "frame-%03d.png".format(Locale.ROOT, index)
                    File(directory, name).outputStream().use {
                        frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    writer.println("$name,${(frame.start - trigger) / 1e6},${(frame.end - trigger) / 1e6}")
                }
            }
        } finally {
            running.set(false)
            worker.join(5_000)
            frames.forEach { it.bitmap.recycle() }
            try {
                if (originalSystemLocales != null) changeSystemLocales(instrumentation, originalSystemLocales)
            } finally {
                compose.runOnUiThread { api.setPreference(originalPreference) }
            }
        }
    }

    private fun LocaleList.withPrimaryLanguage(language: String): LocaleList {
        val ordered = (0 until size()).map { get(it) }.sortedBy { it.language != language }
        check(ordered.first().language == language) { "System locale list must include $language" }
        return LocaleList(*ordered.toTypedArray())
    }
}
