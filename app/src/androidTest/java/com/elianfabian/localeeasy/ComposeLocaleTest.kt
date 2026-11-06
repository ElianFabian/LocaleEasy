package com.elianfabian.localeeasy

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import android.os.Build
import android.app.LocaleManager
import android.view.View
import androidx.test.espresso.Espresso
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import com.elianfabian.locale_easy.LocaleEasy
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ComposeLocaleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun cleanup() { compose.runOnUiThread {
        LocaleEasy.getInstance().followSystem()
        LocaleEasy.getInstance().useFrameworkLocaleRecreation = Build.VERSION.SDK_INT >= 33
    } }

    @Test fun composeNavigationAndTranslationSurviveLocaleRecreation() {
        // Read the initial button through the activity to avoid assuming device language.
        compose.onNodeWithText(compose.activity.getString(R.string.compose_sample_btn)).performClick()
        compose.runOnUiThread { LocaleEasy.getInstance().setLocale(Locale.FRENCH) }
        compose.waitUntil(15_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Écran Compose")).fetchSemanticsNodes().isNotEmpty()
        }
        // The settings button exists on both screens; the Compose title proves navigation survived.
        compose.onNodeWithText("Écran Compose").assertExists()
    }

    @Test fun manualBackendPreservesComposeNavigationOnModernAndroid() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        compose.runOnUiThread { LocaleEasy.getInstance().useFrameworkLocaleRecreation = false }
        compose.waitForIdle()
        composeNavigationAndTranslationSurviveLocaleRecreation()
    }

    @Test fun selectorUsesBothBackendsWithoutNavigatingAway() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val api = LocaleEasy.getInstance()
        for ((framework, language) in listOf(false to "French", true to "Italian", false to "Spanish")) {
            compose.onNodeWithTag("recreation_method").performClick()
            compose.onNodeWithTag(if (framework) "recreation_framework" else "recreation_manual").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(compose.activity.getString(R.string.open_settings)).performClick()
            val before = compose.activity
            val taskId = before.taskId
            val root = before.findViewById<View>(R.id.main_compose_view)
            compose.onNodeWithText(language).performClick()
            compose.waitUntil(15_000) { compose.activity !== before }
            compose.waitForIdle()
            assertNotSame(root, compose.activity.findViewById<View>(R.id.main_compose_view))
            assertEquals(taskId, compose.activity.taskId)
            assertEquals(before.componentName, compose.activity.componentName)
            assertEquals(framework, api.useFrameworkLocaleRecreation)
            val expected = when (language) { "French" -> "fr"; "Italian" -> "it"; else -> "es" }
            assertEquals(expected, compose.activity.resources.configuration.locales[0].language)
            val frameworkLocales = compose.activity.getSystemService(LocaleManager::class.java).applicationLocales
            if (framework) assertEquals(expected, frameworkLocales[0].language) else assertTrue(frameworkLocales.isEmpty)
            Espresso.pressBack() // The restored settings sheet closes; the same home screen remains.
            compose.onNodeWithTag("recreation_method").assertExists()
        }
    }

    @Test fun legacySelectorDisablesFrameworkChoice() {
        assumeTrue(Build.VERSION.SDK_INT < 33)
        compose.onNodeWithTag("recreation_method").performClick()
        compose.onNodeWithTag("recreation_framework").assertIsNotEnabled()
    }
}
