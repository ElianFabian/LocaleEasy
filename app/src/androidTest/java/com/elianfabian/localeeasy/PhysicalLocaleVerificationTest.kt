package com.elianfabian.localeeasy

import android.Manifest
import android.app.Instrumentation
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.LocalePreference
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale

@SdkSuppress(minSdkVersion = 24)
class PhysicalLocaleVerificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val api = LocaleEasy.getInstance()

    @Test
    fun followsGlobalLanguageInForeground() = verifyGlobalChange(background = false, fixed = false)

    @Test
    fun followsGlobalLanguageAfterReturningToTheApp() = verifyGlobalChange(background = true, fixed = false)

    @Test
    fun preservesFixedLanguageInForeground() = verifyGlobalChange(background = false, fixed = true)

    @Test
    fun preservesFixedLanguageAfterReturningToTheApp() = verifyGlobalChange(background = true, fixed = true)

    @Test
    fun restoreGlobalLanguageList() {
        val arguments = InstrumentationRegistry.getArguments()
        val tags = arguments.getString("restoreSystemLocales")
        assumeTrue(arguments.getString("restoreOnly") == "true" && !tags.isNullOrBlank())
        val original = LocaleList.forLanguageTags(tags)
        changeSystemLocales(instrumentation, original)
        awaitCondition("system locale flow reports the restored primary language") {
            api.systemLocaleList.value.firstOrNull()?.language == original[0].language
        }
    }

    private fun verifyGlobalChange(background: Boolean, fixed: Boolean) {
        val originalTags = InstrumentationRegistry.getArguments().getString("restoreSystemLocales")
        assumeTrue(!originalTags.isNullOrBlank())
        val original = LocaleList.forLanguageTags(originalTags)
        val target = LocaleList(
            *original.toLanguageTags().split(',').map(Locale::forLanguageTag)
                .sortedBy { it.language != "fr" }.toTypedArray()
        )
        assertEquals("This test expects French to be present in the device's language list", "fr", target[0].language)
        assertTrue("French must not be the initial primary system language", original[0].language != "fr")
        val originalPreference = api.preference.value
        val expected = if (fixed) Locale.ITALIAN else target[0]
        val expectedText = api.localizedContext(instrumentation.targetContext, expected)
            .getString(R.string.string_resource_example)
        try {
            instrumentation.runOnMainSync {
                if (fixed) api.setAppLocale(Locale.ITALIAN) else api.followSystem()
            }
            ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(
                        if (fixed) "it" else original[0].language,
                        activity.resources.configuration.locales[0].language,
                    )
                    activity.findViewById<EditText>(R.id.locale_fixture_input).setText("keep input")
                }
                if (background) {
                    runShell("am start -W -a android.settings.LOCALE_SETTINGS")
                    awaitCondition("Activity stopped behind Android Settings") { scenario.state == Lifecycle.State.CREATED }
                }
                changeSystemLocales(instrumentation, target)
                awaitCondition("system locale flow reports French") { api.systemLocaleList.value.firstOrNull()?.language == "fr" }
                assertEquals(expected.language, api.effectiveLocaleList.value.first().language)
                if (background) {
                    runShell("input keyevent KEYCODE_BACK")
                    awaitCondition("Activity resumed after returning from Android Settings") {
                        scenario.state == Lifecycle.State.RESUMED
                    }
                }
                awaitCondition("activity renders ${expected.language}") {
                    var rendered = false
                    scenario.onActivity { activity ->
                        rendered = activity.findViewById<TextView>(R.id.locale_fixture_label)
                            .text.toString() == expectedText
                    }
                    rendered
                }
                scenario.onActivity { activity ->
                    assertEquals(expected.language, activity.resources.configuration.locales[0].language)
                    assertEquals("keep input", activity.findViewById<EditText>(R.id.locale_fixture_input).text.toString())
                }
                assertEquals(
                    if (fixed) LocalePreference.AppLocales(listOf(Locale.ITALIAN)) else LocalePreference.FollowSystem,
                    api.preference.value,
                )
            }
        } finally {
            try {
                changeSystemLocales(instrumentation, original)
                awaitCondition("system locale flow reports the original language") {
                    api.systemLocaleList.value.firstOrNull()?.language == original[0].language
                }
            } finally {
                instrumentation.runOnMainSync { api.setPreference(originalPreference) }
            }
        }
    }

    private fun runShell(command: String) {
        val result = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting until $description", condition())
    }
}

// The opt-in instrumentation calls the same framework entry point as Android Settings.
internal fun changeSystemLocales(instrumentation: Instrumentation, locales: LocaleList) {
    val automation = instrumentation.uiAutomation
    automation.adoptShellPermissionIdentity(Manifest.permission.CHANGE_CONFIGURATION, Manifest.permission.WRITE_SETTINGS)
    try {
        Class.forName("com.android.internal.app.LocalePicker")
            .getMethod("updateLocales", LocaleList::class.java)
            .invoke(null, locales)
    } finally {
        automation.dropShellPermissionIdentity()
    }
}
