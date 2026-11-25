package com.elianfabian.localeeasy

import android.app.Activity
import android.app.Instrumentation
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
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
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsStringsXmlLanguages() {
        compose.onNodeWithText(compose.activity.getString(R.string.home_strings_xml_languages, "en, es, fr, it, pt"))
            .assertIsDisplayed()
    }

    @Test fun homeOpensSettings() {
        tapText(R.string.open_settings)
        assertSettingsVisible()
        val localeEasy = LocaleEasy.getInstance()
        val systemTags = localeEasy.systemLocaleList.value.joinToString { it.toLanguageTag() }
        val catalogTags = localeEasy.localeCatalog.supportedLocales.joinToString { it.toLanguageTag() }
        assertLocaleSummary(R.string.state_system_locales, systemTags)
        assertLocaleSummary(R.string.state_catalog_locales, catalogTags)
        assertLocaleSummary(R.string.state_catalog_default, "en")
        if (Build.VERSION.SDK_INT < 33) {
            compose.onNodeWithText(compose.activity.getString(R.string.open_android_languages)).assertDoesNotExist()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun opensTheAndroidLanguagePickerForTheDeclaredCatalog() {
        val api = LocaleEasy.getInstance()
        val androidLocales = api.androidSupportedLocaleList.value as
            com.elianfabian.locale_easy.AndroidSupportedLocales.Configured
        assertEquals(api.localeCatalog.supportedLocales.toSet(), androidLocales.locales.toSet())
        tapText(R.string.open_settings)
        assertSettingsVisible()
        val selected = api.selectedAndroidAppLocale.value?.toLanguageTag()
            ?: compose.activity.getString(R.string.android_locale_uses_device)
        assertLocaleSummary(R.string.state_android_locale, selected)
        assertLocaleSummary(
            R.string.state_android_supported_locales,
            api.localeCatalog.supportedLocales.map { it.toLanguageTag() }.sorted().joinToString(),
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Settings.ACTION_APP_LOCALE_SETTINGS).apply { addDataScheme("package") }
        val monitor = instrumentation.addMonitor(
            filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true,
        )
        try {
            tapText(R.string.open_android_languages)
            compose.waitUntil(5_000) { monitor.hits > 0 }
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
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

    private fun assertLocaleSummary(resource: Int, value: String) {
        compose.onNodeWithText(compose.activity.getString(resource, value))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
