package com.elianfabian.localeeasy

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.LocalePreference
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocale
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class ComposeLocaleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val traceBack = InstrumentationRegistry.getArguments().getString("traceBack") == "true"

    @After fun cleanup() { compose.runOnUiThread {
        LocaleEasy.getInstance().followSystem()
    } }

    @Test fun composeNavigationAndTranslationSurviveLocaleRecreation() {
        compose.onNodeWithText(compose.activity.getString(R.string.compose_sample_btn)).performClick()
        compose.runOnUiThread { LocaleEasy.getInstance().setAppLocale(Locale.FRENCH) }
        compose.waitUntil(15_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Écran Compose")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Écran Compose").assertExists()
    }

    @Test fun languageControlsUseLocalRecreationOnTheSameScreen() {
        compose.onNodeWithText(compose.activity.getString(R.string.local_recreation_status)).assertExists()
        for (language in listOf("French", "Italian", "Spanish")) {
            compose.onNodeWithText(compose.activity.getString(R.string.open_settings)).performClick()
            val before = compose.activity
            val taskId = before.taskId
            val root = before.findViewById<View>(R.id.main_compose_view)
            traceWindows("$language before selection")
            compose.onNodeWithText(language).performClick()
            compose.waitUntil(15_000) { compose.activity !== before }
            compose.waitForIdle()
            assertNotSame(root, compose.activity.findViewById<View>(R.id.main_compose_view))
            assertEquals(taskId, compose.activity.taskId)
            assertEquals(before.componentName, compose.activity.componentName)
            if (Build.VERSION.SDK_INT >= 33) {
                assertTrue(compose.activity.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
            }
            pressBackFromSettings(language)
            compose.onNodeWithText(compose.activity.getString(R.string.local_recreation_status)).assertExists()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.open_settings)).performClick()
        val before = compose.activity
        compose.onNodeWithText(before.getString(R.string.follow_system_locale)).performClick()
        compose.waitUntil(15_000) { compose.activity !== before }
        assertEquals(LocalePreference.FollowSystem, LocaleEasy.getInstance().preference.value)
    }

    @Test fun languageControlsToggleAnOrderedSelectionAndReturnToFollowSystem() {
        val api = LocaleEasy.getInstance()
        compose.runOnUiThread { api.followSystem() }
        compose.onNodeWithText(compose.activity.getString(R.string.open_settings)).performClick()
        val spanish = Locale.forLanguageTag("es")

        followSystemChip().assertIsSelected()
        toggleLocaleAndWait("Spanish")
        toggleLocaleAndWait("French")
        assertEquals(LocalePreference.AppLocales(listOf(spanish, Locale.FRENCH)), api.preference.value)
        compose.onNodeWithText("Spanish").assertIsSelected()
        compose.onNodeWithText("French").assertIsSelected()
        followSystemChip().assertIsNotSelected()

        toggleLocaleAndWait("Spanish")
        assertEquals(LocalePreference.AppLocales(listOf(Locale.FRENCH)), api.preference.value)
        compose.onNodeWithText("Spanish").assertIsNotSelected()

        toggleLocaleAndWait("French")
        assertEquals(LocalePreference.FollowSystem, api.preference.value)
        followSystemChip().assertIsSelected()

        toggleLocaleAndWait("French")
        toggleLocaleAndWait("Spanish")
        toggleLocaleAndWait(compose.activity.getString(R.string.follow_system_locale))
        assertEquals(LocalePreference.FollowSystem, api.preference.value)
        followSystemChip().assertIsSelected()
        compose.onNodeWithText("French").assertIsNotSelected()
        compose.onNodeWithText("Spanish").assertIsNotSelected()
    }

    private fun followSystemChip() =
        compose.onNodeWithText(compose.activity.getString(R.string.follow_system_locale))

    private fun toggleLocaleAndWait(label: String) {
        val before = compose.activity
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(15_000) { compose.activity !== before }
        compose.waitForIdle()
    }

    private fun pressBackFromSettings(language: String) {
        if (!traceBack) {
            Espresso.pressBack()
            return
        }

        val label = language.lowercase(Locale.ROOT)
        traceWindows("$language before Back")
        if (InstrumentationRegistry.getArguments().getString("backInput") == "system") {
            if (language == "French") captureScreen("$label-before-system-back")
            sendSystemBack()
            waitForSettingsDismissal()
            traceWindows("$language after system Back")
            if (language == "French") captureScreen("$label-after-system-back")
            return
        }

        try {
            Espresso.pressBack()
        } catch (failure: RuntimeException) {
            Log.e(BACK_TRACE, "$language Espresso Back failed", failure)
            runCatching {
                traceWindows("$language after Espresso failure")
                captureScreen("$label-espresso-failure")
                sendSystemBack()
                waitForSettingsDismissal()
                traceWindows("$language after recovery Back")
                captureScreen("$label-after-recovery-back")
            }.onFailure { diagnosticError ->
                Log.e(BACK_TRACE, "$language recovery Back did not dismiss the panel", diagnosticError)
                captureScreen("$label-recovery-failure")
            }
            throw failure
        }
        traceWindows("$language after Espresso Back")
    }

    private fun traceWindows(stage: String) {
        if (!traceBack) return
        val activity = compose.activity
        compose.runOnUiThread {
            val decor = activity.window.decorView
            val roots = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews() else listOf(decor)
            val windows = roots.joinToString(" | ") { root ->
                val params = root.layoutParams as? WindowManager.LayoutParams
                val window = if (root === decor) "activity" else root.javaClass.simpleName
                "$window@${System.identityHashCode(root)} type=${params?.type} " +
                    "focus=${root.hasWindowFocus()} shown=${root.isShown}"
            }
            Log.i(BACK_TRACE, "${SystemClock.uptimeMillis()} $stage activity=${System.identityHashCode(activity)} " +
                "decor=${System.identityHashCode(decor)} windows=[$windows]")
        }
    }

    private fun sendSystemBack() {
        val now = SystemClock.uptimeMillis()
        val automation = instrumentation.uiAutomation
        val down = automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true)
        val up = automation.injectInputEvent(
            KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true,
        )
        Log.i(BACK_TRACE, "${SystemClock.uptimeMillis()} system Back down=$down up=$up")
        assertTrue("The system did not accept the Back key", down && up)
    }

    private fun waitForSettingsDismissal() {
        val title = compose.activity.getString(R.string.settings_title)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun captureScreen(name: String) {
        runCatching {
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val directory = File(instrumentation.targetContext.filesDir, "compose-back-trace").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                screenshot.recycle()
            }
        }.onFailure { Log.e(BACK_TRACE, "Failed to capture $name", it) }
    }

    private companion object {
        const val BACK_TRACE = "LocaleEasyBackTrace"
    }
}
