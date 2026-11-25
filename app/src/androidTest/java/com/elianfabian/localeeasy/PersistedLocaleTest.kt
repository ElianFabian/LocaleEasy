package com.elianfabian.localeeasy

import android.util.Log
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocale
import com.elianfabian.locale_easy.LocalePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Run seed and verify in separate instrumentation processes, with force-stop in between. */
@RunWith(AndroidJUnit4::class)
class PersistedLocaleTest {
    @Test fun initialLocaleRespectsSavedPreferences() {
        val phase = InstrumentationRegistry.getArguments().getString("initialLocalePhase")
        assumeTrue(phase in listOf("seed", "verify", "verifyUser", "verifyReset", "seedSystem", "verifySystem"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val api = LocaleEasy.getInstance()
        val prefs = app.getSharedPreferences("locale_easy_prefs", 0)
        val french = LocalePreference.AppLocales(listOf(Locale.FRENCH))
        val spanish = LocalePreference.AppLocales(listOf(Locale.forLanguageTag("es")))
        val expected = when (phase) {
            "seedSystem", "verifySystem", "verifyReset" -> LocalePreference.FollowSystem
            "verifyUser" -> spanish
            else -> french
        }
        val expectedInitial = if (phase == "seedSystem" || phase == "verifySystem") null else Locale.FRENCH

        instrumentation.runOnMainSync {
            if (phase == "seed" || phase == "seedSystem") {
                assertEquals(LocalePreference.FollowSystem, api.preference.value)
                assertFalse(prefs.contains("KEY_FOLLOW_SYSTEM_LOCALE"))
                assertEquals(null, api.initialAppLocale)
                if (phase == "seed") api.setInitialLocale(Locale.FRENCH) else api.followSystem()
            }
            api.setInitialLocale(Locale.ITALIAN)
            assertEquals(expected, api.preference.value)
            assertEquals(expectedInitial, api.initialAppLocale)
            assertTrue(prefs.contains("KEY_FOLLOW_SYSTEM_LOCALE"))
        }

        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            val expectedText = when (expected) {
                french -> "Chaîne Localisée: Vert"
                spanish -> "Cadena Localizada: Verde"
                else -> api.localizedContext(app).getString(R.string.string_resource_example)
            }
            scenario.onActivity {
                assertEquals(expectedText, it.findViewById<TextView>(R.id.locale_fixture_label).text.toString())
            }
        }

        if (phase == "verify") {
            instrumentation.runOnMainSync { api.setPreference(spanish) }
        }
        if (phase == "verifyUser") {
            instrumentation.runOnMainSync { api.followSystem() }
        }
        assertEquals(expectedInitial, api.initialAppLocale)
        assertTrue(prefs.edit().commit())
        Log.i("LocaleEasyFixture", "initial locale phase=$phase process=${android.os.Process.myPid()}")
    }

    @Test fun preferenceSurvivesProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("localePhase")
        assumeTrue(phase == "seed" || phase == "verify")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val api = LocaleEasy.getInstance()
        if (phase == "seed") instrumentation.runOnMainSync {
            api.setAppLocale(Locale.FRENCH)
        }
        instrumentation.waitForIdleSync()
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = instrumentation.targetContext.getSystemService(LocaleManager::class.java)
            assertTrue(manager.applicationLocales.isEmpty)
        }
        assertEquals(LocalePreference.AppLocales(listOf(Locale.FRENCH)), api.preference.value)
        assertEquals(listOf(Locale.FRENCH), api.effectiveLocaleList.value)
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals("Chaîne Localisée: Vert", it.findViewById<TextView>(R.id.locale_fixture_label).text.toString())
            }
        }
        Log.i("LocaleEasyFixture", "persistence phase=$phase process=${android.os.Process.myPid()}")
    }

    @Test fun restoresLegacyDuplicatesBeforeApplyingLocales() {
        val phase = InstrumentationRegistry.getArguments().getString("duplicatePhase")
        assumeTrue(phase == "seed" || phase == "verify")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val prefs = app.getSharedPreferences("locale_easy_prefs", 0)
        if (phase == "seed") {
            assertTrue(prefs.edit().putBoolean("KEY_FOLLOW_SYSTEM_LOCALE", false)
                .putString("KEY_LANGUAGES", "fr,fr,fr-CA").commit())
        } else {
            assertEquals(listOf(Locale.FRENCH, Locale.CANADA_FRENCH), LocaleEasy.getInstance().effectiveLocaleList.value)
            assertEquals("fr,fr-CA", prefs.getString("KEY_LANGUAGES", null))
            assertEquals("Chaîne Localisée: Vert", app.getString(R.string.string_resource_example))
        }
    }

    @Test fun localModePreservesAnIndependentFrameworkSelection() {
        val phase = InstrumentationRegistry.getArguments().getString("ownershipPhase")
        assumeTrue(Build.VERSION.SDK_INT >= 33 && (phase == "seed" || phase == "verify"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val api = LocaleEasy.getInstance()
        val manager = app.getSystemService(LocaleManager::class.java)
        if (phase == "seed") {
            instrumentation.runOnMainSync {
                api.setAppLocale(Locale.FRENCH)
                manager.applicationLocales = LocaleList(Locale.ITALIAN)
            }
            // Flush the local selection before the next process starts.
            assertTrue(app.getSharedPreferences("locale_easy_prefs", 0).edit().commit())
        } else {
            assertEquals(LocaleList(Locale.ITALIAN), manager.applicationLocales)
            assertEquals(listOf(Locale.FRENCH), api.effectiveLocaleList.value)
            ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
                scenario.onActivity {
                    assertEquals("Chaîne Localisée: Vert", it.findViewById<TextView>(R.id.locale_fixture_label).text.toString())
                    api.followSystem()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(api.systemLocaleList.value, androidx.core.os.ConfigurationCompat
                        .getLocales(it.resources.configuration).let { locales -> List(locales.size()) { locales[it] } })
                    assertEquals(LocaleList(Locale.ITALIAN), manager.applicationLocales)
                }
            }
        }
    }
}
