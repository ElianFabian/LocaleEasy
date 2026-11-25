package com.elianfabian.locale_easy

import android.os.Build
import android.widget.TextView
import androidx.core.os.ConfigurationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LegacyLocaleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val api get() = LocaleEasy.getInstance()
    @Before fun reset() { instrumentation.runOnMainSync { api.followSystem() } }
    @After fun cleanup() { instrumentation.runOnMainSync { api.followSystem() } }

    @Test fun startupIsIdempotentAndContextResourcesAreLocalized() {
        val before = api
        instrumentation.runOnMainSync {
            LocaleEasy.install(instrumentation.targetContext)
            assertSame(before, api)
            api.setAppLocale(Locale.FRENCH)
            assertEquals("French fixture", api.localizedContext(instrumentation.targetContext).getString(R.string.locale_easy_fixture_label))
        }
    }

    @Test fun missingCatalogKeepsFollowSystemWithNoDefaultLocale() {
        assertTrue(api.localeCatalog.supportedLocales.isEmpty())
        assertNull(api.localeCatalog.defaultLocale)
        assertEquals(api.systemLocaleList.value, api.effectiveLocaleList.value)
        if (Build.VERSION.SDK_INT < 33) {
            assertEquals(AndroidSupportedLocales.Unsupported, api.androidSupportedLocaleList.value)
            assertNull(api.selectedAndroidAppLocale.value)
        } else {
            assertEquals(AndroidSupportedLocales.NotConfigured, api.androidSupportedLocaleList.value)
        }
    }

    @Test fun relocalizesAnOldContextWhenReturningToFollowSystem() {
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext.applicationContext
            val followed = api.localizedContext(base)
            val original = followed.getString(R.string.locale_easy_fixture_label)
            val other = if (original == "French fixture") Locale.forLanguageTag("he-IL") else Locale.FRENCH
            api.setAppLocale(other)
            val previous = api.localizedContext(base)
            assertNotEquals(original, previous.getString(R.string.locale_easy_fixture_label))
            api.followSystem()
            assertEquals(original, api.localizedContext(previous).getString(R.string.locale_easy_fixture_label))
        }
    }

    @Test fun explicitLookupPreservesHostOverridesAndPreference() {
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext.applicationContext
            val host = base.createConfigurationContext(android.content.res.Configuration().apply {
                fontScale = 1.75f
                uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
            })
            val preference = api.preference.value
            val effective = api.effectiveLocaleList.value
            val french = api.localizedContext(host, Locale.FRENCH)
            assertEquals("French fixture", french.getString(R.string.locale_easy_fixture_label))
            assertEquals(1.75f, french.resources.configuration.fontScale, 0f)
            assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES,
                french.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            assertEquals(preference, api.preference.value)
            assertEquals(effective, api.effectiveLocaleList.value)
        }
    }

    @Test fun explicitLocaleListKeepsOrderAndDeduplicatesWithoutChangingAppLocale() {
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext.applicationContext
            api.setAppLocale(Locale.ENGLISH)
            val preference = api.preference.value
            val effective = api.effectiveLocaleList.value
            val processDefault = Locale.getDefault()
            val hebrew = Locale.forLanguageTag("he-IL")
            for ((preferred, expectedText) in listOf(
                listOf(Locale.FRENCH, Locale.FRENCH, hebrew) to "French fixture",
                listOf(hebrew, Locale.FRENCH, hebrew) to "Hebrew fixture",
            )) {
                val context = api.localizedContext(base, preferred)
                val configLocales = ConfigurationCompat.getLocales(context.resources.configuration)
                val expected = if (Build.VERSION.SDK_INT >= 24) preferred.distinct() else preferred.take(1)
                assertEquals(expected, List(configLocales.size()) { configLocales[it] })
                assertEquals(expectedText, context.getString(R.string.locale_easy_fixture_label))
            }
            assertEquals(preference, api.preference.value)
            assertEquals(effective, api.effectiveLocaleList.value)
            assertEquals(processDefault, Locale.getDefault())
            assertEquals("English fixture", base.getString(R.string.locale_easy_fixture_label))
        }
    }

    @Test fun explicitLocaleListFallsBackThroughAndroid() {
        val base = instrumentation.targetContext.applicationContext
        val context = api.localizedContext(base, listOf(Locale.forLanguageTag("qaa"), Locale.FRENCH))
        val expected = if (Build.VERSION.SDK_INT >= 24) "French fixture" else "English fixture"
        assertEquals(expected, context.getString(R.string.locale_easy_fixture_label))
    }

    @Test fun explicitLocaleListRejectsEmptyAndInvalidInput() {
        val base = instrumentation.targetContext.applicationContext
        assertThrows(IllegalArgumentException::class.java) { api.localizedContext(base, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            api.localizedContext(base, listOf(Locale.FRENCH, Locale.ROOT))
        }
    }

    @Test fun catalogPreservesMatchingLocaleOrderAndScripts() {
        val traditional = Locale.forLanguageTag("zh-Hant")
        val catalog = LocaleCatalog(Locale.ENGLISH, listOf(Locale.FRENCH, traditional, Locale.ENGLISH))
        val unsupported = Locale.GERMAN
        assertEquals(listOf(Locale.ENGLISH, Locale.FRENCH, traditional), catalog.supportedLocales)
        val system = listOf(unsupported, Locale.CANADA_FRENCH, traditional)
        val following = LocaleState(LocalePreference.FollowSystem, system, catalog)
        assertEquals(system, following.systemLocales)
        assertEquals(listOf(Locale.CANADA_FRENCH, traditional), following.effectiveLocales)
        assertEquals(listOf(Locale.ENGLISH),
            LocaleState(LocalePreference.FollowSystem, listOf(Locale.SIMPLIFIED_CHINESE), catalog).effectiveLocales)
        assertEquals(listOf(unsupported),
            LocaleState(LocalePreference.AppLocales(listOf(unsupported)), system, catalog).effectiveLocales)
    }

    @Test fun legacyAndModernLanguageTagsResolveToTheSamePreference() {
        instrumentation.runOnMainSync {
            for ((legacy, modern) in listOf("iw-IL" to "he-IL", "in-ID" to "id-ID", "ji" to "yi")) {
                api.setLanguageTag(legacy)
                val selected = api.preference.value
                api.setLanguageTag(modern)
                assertEquals("Alias pair $legacy and $modern", selected, api.preference.value)
                assertEquals(listOf(Locale.forLanguageTag(modern)), api.effectiveLocaleList.value)
            }
        }
    }

    @Test fun localizedContextLoadsLegacyAndBcp47ResourceFolders() {
        instrumentation.runOnMainSync {
            for ((tag, expected) in listOf(
                "he-IL" to "Hebrew fixture",
                "sr-Latn-RS" to "Serbian Latin fixture",
                "es-419" to "Latin American Spanish fixture",
            )) {
                api.setLanguageTag(tag)
                val resources = api.localizedContext(instrumentation.targetContext).resources
                assertEquals("Resource for $tag", expected, resources.getString(R.string.locale_easy_fixture_label))
            }
        }
    }

    @Test fun plainActivityRecreationAppliesFrench() {
        ActivityScenario.launch(LegacyLocaleFixtureActivity::class.java).use { scenario ->
            var original: LegacyLocaleFixtureActivity? = null
            scenario.onActivity { original = it; api.setAppLocale(Locale.FRENCH) }
            val deadline = System.nanoTime() + 15_000_000_000L
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { ready = it !== original && it.findViewById<TextView>(android.R.id.text1).text == "French fixture" }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Replacement activity must have French resources", ready)
        }
    }
}
