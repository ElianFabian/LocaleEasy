package com.elianfabian.localeeasy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.junit.Rule
import org.junit.Test

class SettingsControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeOpensSettings() {
        tapText(R.string.open_settings)
        assertSettingsVisible()
    }

    @Test fun composeSampleOpensSettings() {
        tapText(R.string.compose_sample_btn)
        compose.onNodeWithText(compose.activity.getString(R.string.open_settings))
            .performTouchInput { click() }
        assertSettingsVisible()
    }

    @Test fun activitySampleOpensSettings() {
        tapText(R.string.activity_sample_btn)
        tapNativeSettings()
    }

    @Test fun fragmentSampleOpensSettings() {
        tapText(R.string.fragment_sample_btn)
        tapNativeSettings(inDialog = true)
    }

    @Test fun webViewSampleOpensSettings() {
        tapText(R.string.webview_sample_btn)
        tapNativeSettings(inDialog = true)
    }

    private fun tapText(resource: Int) {
        compose.onNodeWithText(compose.activity.getString(resource))
            .performScrollTo()
            .performTouchInput { click() }
    }

    private fun tapNativeSettings(inDialog: Boolean = false) {
        val button = onView(withId(R.id.btnSettings))
        if (inDialog) button.inRoot(isDialog())
        button.perform(ViewActions.click())
        assertSettingsVisible()
    }

    private fun assertSettingsVisible() {
        val title = compose.onNodeWithText(compose.activity.getString(R.string.settings_title))
        compose.waitUntil(5_000) { title.isDisplayed() }
        title.assertIsDisplayed()
    }
}
