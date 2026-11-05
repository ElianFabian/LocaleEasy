package com.elianfabian.localeeasy

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.elianfabian.locale_easy.LocaleEasy
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ComposeLocaleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun cleanup() { compose.runOnUiThread { LocaleEasy.getInstance().followSystem() } }

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
}
