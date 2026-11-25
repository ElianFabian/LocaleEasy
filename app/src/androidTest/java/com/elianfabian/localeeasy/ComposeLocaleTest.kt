package com.elianfabian.localeeasy

import android.app.LocaleManager
import android.os.Build
import android.view.View
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import com.elianfabian.locale_easy.LocaleEasy
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ComposeLocaleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun cleanup() { compose.runOnUiThread {
        LocaleEasy.getInstance().followSystem()
    } }

    @Test fun composeNavigationAndTranslationSurviveLocaleRecreation() {
        compose.onNodeWithText(compose.activity.getString(R.string.compose_sample_btn)).performClick()
        compose.runOnUiThread { LocaleEasy.getInstance().setLocale(Locale.FRENCH) }
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
            compose.onNodeWithText(language).performClick()
            compose.waitUntil(15_000) { compose.activity !== before }
            compose.waitForIdle()
            assertNotSame(root, compose.activity.findViewById<View>(R.id.main_compose_view))
            assertEquals(taskId, compose.activity.taskId)
            assertEquals(before.componentName, compose.activity.componentName)
            if (Build.VERSION.SDK_INT >= 33) {
                assertTrue(compose.activity.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
            }
            Espresso.pressBack()
            compose.onNodeWithText(compose.activity.getString(R.string.local_recreation_status)).assertExists()
        }
    }
}
